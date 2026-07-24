# Emira crank, cam, and engine-event architecture

Target: 2022 Lotus Emira V6 ROW application `8896915220A_ROW`, MPC5777C/G6.

This note traces the application-side engine-position pipeline from eTPU initialization through crank/cam decoding, synchronization, per-cylinder event scheduling, VVT capture, and downstream misfire/knock consumers. Logical eTPU channels and memory addresses are reported directly. Physical connector pins and wire identities are intentionally not assigned.

## Executive model

The Emira delegates crank-angle timing to eTPU hardware and uses the CPU as a supervisory/event consumer:

1. eTPU-A channel 0 decodes the crank pattern and publishes tooth/event state and a 24-bit time base.
2. eTPU-A channels 2, 4, 5, and 6 capture four cam signals used for synchronization and four VVT phase measurements.
3. The crank event handler derives a 720.0-degree cylinder phase, engine period, speed, per-cylinder timing intervals, and sync/fault state.
4. eTPU-A channels 7-12 schedule six logical ignition events.
5. eTPU-A channels 14-19 schedule six logical injection events.
6. Per-cylinder crank intervals feed a misfire/roughness calculation; VVT captures feed bank/cam phase control and diagnostics.

The CPU does not bit-bang teeth or wait for target angles. It writes angle and duration parameters into eTPU parameter RAM and issues channel host-service requests. eTPU then executes those events independently at crank-angle time.

## eTPU memory and channel model

`FUN_00a697f0` establishes the engine eTPU address map (`emira.c:70315-70338`):

| Address | Role |
|---:|---|
| `0xC3FC0000` | eTPU-A control/channel register base (`DAT_40003580`) |
| `0xC3FD0000` | second engine eTPU module/register base (`DAT_40003570`) |
| `0xC3FC8000` | eTPU-A parameter RAM base (`DAT_4000357C`) |
| `0xC3FCC000` | alternate/24-bit parameter access base (`DAT_40003578`) |
| `0xC3FC97FC` | configured end of eTPU-A parameter allocation |

`eTPU_write_32bit` locates a channel's allocated parameter block from its channel configuration entry and writes a 32-bit value at a byte offset (`emira.c:70810-70817`). Event writers consistently use offsets `0x01` and `0x05`, followed by a host-service request through the channel's `+0x408` register. The unaligned offsets are an artifact of eTPU's 24-bit parameter-RAM view, not ordinary CPU structure alignment.

The engine-event setup is in `FUN_00a0c548` (`0x00A0C548`, `emira.c:28000-28153`). The logical map is:

| eTPU-A channel | INTC/vector priority register | Priority | Confirmed application role |
|---:|---:|---:|---|
| 0 | `0xFFF48084` | 14 | master crank/tooth decoder |
| 2 | `0xFFF48086` | 14 | cam/VVT capture 0 |
| 4 | `0xFFF48088` | 14 | cam/VVT capture 1 |
| 5 | `0xFFF48089` | 14 | cam/VVT capture 2 |
| 6 | `0xFFF4808A` | 14 | cam/VVT capture 3 |
| 7-12 | `0xFFF4808B..0xFFF48090` | 14 once synchronized | six logical ignition outputs |
| 14-19 | `0xFFF48092..0xFFF48097` | 14 once synchronized | six logical injection outputs |
| 26 | `0xFFF4809E` | 14 | auxiliary engine-angle event; exact role unresolved |
| 27 | `0xFFF4809F` | 14 | auxiliary engine-angle event; exact role unresolved |

The vector relationship is direct: eTPU-A channel `n` maps to priority byte `0xFFF48084 + n`. Channel functions are configured through `FUN_00a698a8`, `FUN_00a69974`, `FUN_00a69a48`, and `FUN_00a69ddc` (`emira.c:70342-70556`). These wrappers allocate parameter RAM and write eTPU function/mode fields; the actual eTPU microcode is not decompiled C.

The application also configures event functions on a second eTPU context, including logical channel 1 and vector `0x1B3`, plus separate handlers at vectors `0x45` and `0x175`. Their CPU-side users do not establish crank/cam semantics strongly enough to include them in the primary channel map.

## Crank decoder and tooth model

### Initialization

Channel 0 is initialized by:

```c
FUN_00a698a8(0, 2, 3, 0, &DAT_00196e6b, 0, 0, 0x800000, 0x100);
DAT_fff48084 = 0xe;
```

The calibration/constant block at `0x00196E6B` is passed to the crank eTPU function. The numeric function mode and the tooth-pattern table itself are not decoded, so this pass does not label the wheel as a specific `N-minus-M` pattern from initialization alone.

### Event handler

`FUN_00a0cb4c` (`0x00A0CB4C`, `emira.c:28155-28620`) is the CPU crank-event consumer. It reads channel-0/eTPU globals through:

- `FUN_00a6a8e0(0)`: decoder state/count;
- `FUN_00a6a8bc(0x2D)`: current tooth/event ordinal;
- `FUN_00a6a8e0(1)`: sync state;
- `FUN_00a6a8bc(0x35)`, `0x3D`, and `0x41`: captured angle/time values.

All timestamps are masked to 24 bits by the access helper. `engine_period1` is measured between tooth ordinals `0x08`, `0x14`, `0x20`, `0x2C`, `0x38`, and `0x44`. Those six ordinals are separated by 12 counts and represent six equal cylinder intervals. Intermediate ordinals `0x10`, `0x1C`, `0x28`, `0x34`, `0x40`, and `0x04` are assigned logical cylinder indices `5,0,1,2,3,4` and their elapsed 24-bit intervals are handed to `FUN_00a7059c` (`emira.c:28203-28263`).

The handler also recognizes cylinder-reference ordinals `0x07`, `0x13`, `0x1F`, `0x2B`, `0x37`, and `0x43` and keeps sequence/fault counters for each. This proves a 12-event-per-cylinder crank-domain cadence, but not the physical tooth count or edge polarity.

## Speed derivation and stopped-engine behavior

The scheduled foreground routine `FUN_00a0ea38` converts the crank interval into speed (`emira.c:29090-29168`):

```text
engine_speed         = 200,000,000 / engine_period1
obd_ii_engine_speed  = engine_speed * 4
```

`engine_speed` is whole RPM; the OBD value is quarter-RPM. `engine_period1` is accepted only in `1..0xFFFFFE`. Otherwise both speeds become zero and the derived period/roughness variable `DAT_40003424` becomes `0xFFFF`.

The crank event handler sets `engine_period1 = 0xFFFFFFFF` whenever the decoder state is below 4. Repeated pattern/sequence failures call `FUN_00a0c548`, which clears speed, period, sync state, cam state, per-cylinder enable state, and rebuilds all engine eTPU channels. This is a full event-subsystem reinitialization, not merely a diagnostic flag.

## Synchronization and cylinder phase

The primary synchronization indicators are:

- `DAT_40005F14`: eTPU sync/decoder state; value `4` is required for full operation;
- `DAT_400033F8`: CPU-side synchronized/engine-event-enabled latch;
- `DAT_40005DEC`: decoder confidence/state count; below 2 forces reinitialization;
- `DAT_40003428`: engine-running/valid-period state derived with calibrated hysteresis;
- `DAT_40003638`: six-bit cylinder-reference sequence fault set;
- `DAT_4000363C`: initialization/sync failure latch.

Once `DAT_40005F14 == 4`, `FUN_00a0cb4c` requires valid coding/calibration integrity and a successful local synchronization-state predicate (`FUN_00a8fc14`) before setting `DAT_400033F8 = 1` (`emira.c:28552-28568`). It then:

1. initializes six per-cylinder ignition state records through `FUN_00a93adc`;
2. configures eTPU ignition channels 7-12 around their cylinder references;
3. initializes injection triggers and channels 14-19;
4. enables channel event bits and auxiliary channels 26/27.

If those conditions fail, `DAT_4000363C` is set and full per-cylinder scheduling is withheld.

### Logical cylinder references

All engine angles use `0x1C20 = 7200`, i.e. one tenth degree across a 720-degree four-stroke cycle. The six logical cylinder references are:

| Logical cylinder index | Reference value | Angle |
|---:|---:|---:|
| 0 | `0x060E` | 155.0° |
| 1 | `0x0ABE` | 275.0° |
| 2 | `0x0F6E` | 395.0° |
| 3 | `0x141E` | 515.0° |
| 4 | `0x18CE` | 635.0° |
| 5 | `0x015E` | 35.0° |

They are exactly 120.0° apart modulo 720.0°. This is the logical firing sequence used internally. The export does not map indices `0..5` to Lotus/Toyota physical cylinder numbers or bank connector pins.

When partial cylinder availability changes, `FUN_00a0ea38` builds six per-cylinder phase-valid bytes at `0x4000329C..0x400032A1`, preserving symmetric fallback patterns for one through five available cylinders (`emira.c:29234-29318`). Those bytes gate per-cylinder scheduling and are consumed by ignition, fueling, misfire, and diagnostics.

## Cam decoding and VVT position capture

Four capture channels are initialized with the same eTPU cam function:

```text
channel 2 -> `FUN_00a0dd40`
channel 4 -> `FUN_00a0dff0`
channel 5 -> `FUN_00a0e520`
channel 6 -> `FUN_00a0e288`
```

Each handler reads:

- edge/status byte at parameter offset 4;
- sync/quality byte at offset 9;
- 24-bit timestamp at offset 5;
- captured crank angle at offset 1.

Before full crank sync, each cam handler observes edge spacing and direction/state transitions to build a six-state cam pattern and reject implausible sequences. Once the crank decoder state reaches 4, it normalizes the captured angle modulo `0x1C20` and calculates a VVT position relative to a fixed reference:

| eTPU channel | CPU handler | Fixed reference | Published VVT value |
|---:|---|---:|---|
| 2 | `FUN_00a0dd40` (`0x00A0DD40`) | `0x08D0` | `DAT_40003640` |
| 4 | `FUN_00a0dff0` (`0x00A0DFF0`) | `0x06F4` | `DAT_40003642` |
| 5 | `FUN_00a0e520` (`0x00A0E520`) | `0x0858` | `DAT_4000363A` |
| 6 | `FUN_00a0e288` (`0x00A0E288`) | `0x0A38` | `DAT_40003636` |

The four published values are passed onward by `func_0x00a4fe20` and `func_0x00a4fe24` at the end of `FUN_00a0ea38` (`emira.c:29340-29347`). Two correspond to one logical control family and two to another, but bank/intake/exhaust assignment is not provable from these unnamed consumers alone.

Cam event counters are reset at particular crank ordinals (`0x0F`, `0x28`, and `0x04` paths), and timeout/sequence flags ultimately populate `DAT_40003638` and cylinder/cam DTC logic. A cam loss does not immediately erase crank-derived RPM; it degrades synchronization/phase validity and can force the engine-event subsystem back through initialization if aggregate decoder confidence falls.

## Ignition scheduling

eTPU-A channels 7-12 are six ignition event channels. `FUN_00a0c2c8` maps logical cylinder index to channel and fixed reference (`emira.c:27884-27948`):

| Logical cylinder | eTPU channel | Reference |
|---:|---:|---:|
| 0 | 7 | `0x060E` |
| 1 | 8 | `0x0ABE` |
| 2 | 9 | `0x0F6E` |
| 3 | 10 | `0x141E` |
| 4 | 11 | `0x18CE` |
| 5 | 12 | `0x015E` |

It subtracts the requested timing angle from the cylinder reference, wraps modulo `0x1C20`, writes that angle and a dwell/time parameter, then issues a channel host-service request through `FUN_00a69ea0` or `FUN_00a6a020`. `FUN_00a93adc` connects the ignition calculation domain at `0x40013A44...` to this writer (`emira.c:91527-91534`).

The function named `ignition___` is a slower ignition-management calculation and diagnostic routine, not the angle-critical output ISR. The actual edge timing remains in eTPU after the CPU updates the channel parameters.

Per-cylinder inhibit masks are applied in `FUN_00a0bec8`: loss of global sync, cylinder phase invalidity, cut masks, or fault masks set a channel to inactive service state and update `DAT_40003410`/`DAT_40003412`. This is the event-level handoff for spark cuts and cylinder disable.

## Injection scheduling

eTPU-A channels 14-19 map directly to logical cylinders 0-5. `inj_set_trigger` (`0x00A0C3BC`, `emira.c:27950-27978`) adds a bank-dependent calculated offset (`DAT_400037C4` for even logical indices, `DAT_400037C8` for odd indices), wraps modulo 720.0°, selects the corresponding bank pulse width (`DAT_400032D8` or `DAT_400032DC`), applies an optional per-cylinder calibration multiplier, and calls:

```c
inj_write_etpu(cyl + 14, angle, pulse_time_raw);
```

`inj_write_etpu` writes angle at parameter offset 1, duration at offset 5, then issues host service 2 (`emira.c:70584-70596`). The 100 Hz `injection()` calculation derives bank pulse widths and dynamic phase offsets; when synchronized and channel startup is complete it refreshes all six triggers (`emira.c:49740 onward`, trigger calls at `emira.c:50439-50444`).

When the engine-run/sync bit is absent, `injection()` substitutes the minimum `0xA0` pulse-time state and asserts its cut/status bit rather than trusting angle scheduling. Per-cylinder fault and cut masks are also consumed by the eTPU-channel supervisory logic.

## Loss of synchronization and safe behavior

Loss-of-sync handling exists at several layers:

1. Crank decoder state below 4 invalidates `engine_period1`.
2. Foreground speed conversion then publishes zero RPM and invalid period.
3. Decoder confidence below 2 or broken cylinder-reference sequencing calls `FUN_00a0c548`, clearing and rebuilding the engine event subsystem.
4. `DAT_400033F8` is cleared during reset; ignition/injection event channels are not enabled until full sync and integrity checks pass again.
5. Per-cylinder phase bytes become `0xFF` when phase is unknown, and fault/cut masks suppress individual eTPU events.
6. VVT capture state machines retain their own quality/sequence flags and do not manufacture phase from stale timestamps.

The decompile shows no software “coast” mode that continues normal sequential fuel and spark after crank sync is lost. RPM falls to zero through the invalid-period path and the eTPU channel set is rebuilt.

## Misfire and roughness handoff

The crank event handler records one interval for each logical cylinder through `FUN_00a7059c`, rotating indices in firing order. `FUN_00a7059c` shifts the previous interval to `0x400090A0[index]`, stores the new interval in the six-word current array, and publishes the newest index in `DAT_400088F8` (`emira.c:74135-74145`).

`FUN_00a705c8` later consumes those adjacent/current/previous intervals. It computes a cylinder-specific speed-change residual, subtracts learned baselines from the persistent image, applies speed/load thresholds, increments per-cylinder event counts, and sets per-cylinder monitor flags (`emira.c:74684-74740` and following). This is direct evidence that the crank interval stream is the misfire/roughness input.

The two calls made whenever a complete `engine_period1` interval is captured—`FUN_00a1d1cc` and `FUN_00a1bf74`—update filtered period/acceleration state used by torque/roughness control (`emira.c:28203-28211`, function bodies at `emira.c:37367` and `36740`). Their exact division between misfire detection and general rotational dynamics is not fully named.

## Knock-window handoff

The separate `IGNITION_KNOCK_ANALYSIS.md` recovers the CPU-side knock-window calculation: `FUN_00a6deec()` evaluates calibration maps at offsets `0x240e` and `0x252e` around six cylinder centers, feeding an 11-bin, 128-sample DSP pipeline serviced from periodic vector `0x42`. This establishes synchronized six-cylinder windowing without assigning it to an eTPU channel.

The low-level trigger route and vector body remain absent from the export. Engine-event auxiliary channels 26/27 are enabled with the per-cylinder schedulers, but assigning either to knock solely by analogy with Evora would still be guesswork. The physical ADC input, DMA/eMIOS/eTPU trigger, and exact acquisition ISR remain unresolved.

## Confirmed, inferred, and unknown

### Confirmed

- eTPU-A memory/register/parameter bases.
- Channel 0 crank decoding; channels 2/4/5/6 four cam captures.
- Channels 7-12 ignition and 14-19 injection.
- Priority 14 for all mapped crank/cam/fuel/spark event vectors.
- Six logical cylinder references spaced 120.0° over a 720.0° cycle.
- Engine speed formula, stopped-engine invalidation, and full event-subsystem reset on decoder loss.
- Four published VVT phase values and their capture channels.
- Per-cylinder crank interval handoff into misfire/roughness calculations.

### Strong inference

- `DAT_40005F14 == 4` represents fully synchronized crank/cam state.
- Logical cylinder indices follow firing order, not necessarily physical cylinder numbering.
- Channels 2/4/5/6 correspond to the four intake/exhaust cams across two banks.

### Unknown

- Physical crank/cam/coil/injector connector pins and edge polarities.
- Exact crank wheel tooth pattern and missing-tooth geometry.
- Mapping of logical indices 0-5 to physical engine cylinder numbers.
- Bank/intake/exhaust names for each of the four VVT capture channels.
- Exact semantics of auxiliary eTPU channels 26 and 27.
- Knock acquisition trigger route and vector-`0x42` ISR body; the CPU-side window/DSP pipeline is documented separately.
- Complete eTPU microcode behavior; only CPU configuration and parameter exchange are visible.

## Practical implications

Instrumentation should distinguish three layers: raw eTPU decoder state (`DAT_40005DEC`, `DAT_40005F14`, current ordinal `DAT_40005F28`), CPU synchronization/phase state (`DAT_400033F8`, `DAT_40003428`, per-cylinder bytes `0x4000329C..A1`), and output scheduling (channels 7-12 and 14-19). Logging only `engine_speed` hides the transition from tentative tooth lock to full 720-degree phase. For safe bench work, logical channel numbers are reliable targets; physical pin attribution requires the MPC5777 package/pin mux and ECU schematic rather than inference from the Evora.
