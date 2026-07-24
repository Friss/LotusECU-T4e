# 8896915220A ROW start lockout and engine-shutdown analysis

Scope: 2022 Lotus Emira V6 ROW firmware export (`emira.c`). This report traces the evidence-visible
path from boot/application integrity through crank synchronization and combustion enable, then from
ignition-off detection through after-run work, persistent writes, actuator shutdown and power-hold
release.

The export contains both bootloader code at `0x008xxxxx` and the main application at `0x00axxxxx`.
Those trust boundaries are described separately. Line references are to the checked-in export.

## Principal findings

- The bootloader validates an application header and applies its RSA-signature policy before treating
  the main image as valid; the visible policy accepts an all-`0xff` unprovisioned public-key region.
  The application independently tests RAM, continuously checks application/calibration
  integrity, validates coding/learned-data CRCs, and records integrity faults.
- The direct combustion-enable boundary is `DAT_400033f8`. It is set only after the crank/cam event
  decoder reaches a synchronized state, the active calibration CRC/coding gate passes, and a
  synchronization-state predicate is true. At that transition all six ignition and injection events
  are initialized.
- Engine-running state (`DAT_40003428`) is derived separately from valid engine period with calibrated
  on/off thresholds. Thus “synchronized/combustion enabled” and “engine running” are related but not
  identical states.
- The application contains extensive crank/cam sync-loss and cylinder-event validation. Loss of
  decoder confidence resets event scheduling and prevents/revokes normal combustion.
- The ignition/power state machine at `FUN_00a742fc()` has explicit off, wake-delay, run, after-run,
  final-write, and release states. After engine stop it computes a load/coolant-dependent hold time,
  services requested after-run work, writes coding/learned data, and finally releases external
  power.
- The exact starter-solenoid and fuel-pump output authorities are not yet identified. Likewise, no
  application function is proven to implement immobilizer authentication. Body/security-module CAN
  authorization may feed anonymous state, but is not recoverable enough to label.

## Confidence key

- **Confirmed**: direct condition, state transition, hardware/output call, or dataflow is visible.
- **Inferred**: structure is strong but a signal name, physical output, or external participant is
  anonymous.
- **Unknown**: the current export does not distinguish the alternatives safely.

## Boot and application integrity gates

### Bootloader signature boundary

`firmware_integrity_validate()` at `0x00809a44` checks that:

1. the application pointer lies above `0x009fffff` and below `0x00c7fff9`;
2. its first words are ASCII-like header constants `0x464c4153` / `0x48454e44` (“FLASHEND”);
3. `rsa_sign_check()` succeeds.

The complete predicate is visible at lines 13779-13797. `FUN_0080e51c()` exposes this result to the
boot state machine (lines 14971-14983). RSA validation loads the programmed public key, reverses its
endianness, verifies a certificate/signature and checks whether the flash key is blank (lines
19845-20053).

This is confirmed image-validation behavior. RSA enforcement is conditional: as detailed in
`BOOTLOADER_PROGRAMMING_ANALYSIS.md`, `rsa_sign_check()` returns success when the public-key region is
all `0xff`. It is not an immobilizer decision: it validates executable firmware policy, not the
vehicle key or a start request.

### Application startup integrity

The application performs a destructive/restore RAM bit test at the beginning of `main()` and sets
`_DAT_40003284.0` on failure (lines 22496-22545). During the idle side of the phase loop it:

- computes `CRC16_2(init, 0xaa0000)` and sets `_DAT_40003284.5` on mismatch;
- continuously compares CALROM and the active calibration copy, setting `_DAT_40003284.4` if they
  differ while the ECU is not unlocked.

Those checks are at lines 22555-22592.

`init_core_system()` computes `caldata_crc` over the active calibration and calls `COD_unknown()`
plus `FUN_00a12548()` to validate coding/persistent data (lines 23610-23653). `COD_unknown()` copies
the 160-byte coding block, verifies its CRC, checks the model name against the active calibration,
loads defaults on mismatch and sets `DAT_40003908` when coding/model content does not agree (lines
75390-75478).

`FUN_00a12548()` validates two larger learned-data regions with independent stored CRC/length
markers. Invalid content sets reason bits, clears affected learned areas, and reseeds calibration
defaults rather than trusting corrupt state (lines 31373-31655).

### Integrity effect on start

The crank/event routine tests both active-calibration integrity and coding state immediately before
enabling combustion:

```c
((DAT_40003908 == 0 && *(CALBASE+0xfffe) == caldata_crc) || BOOL_40003331)
    && synchronization_state_valid
```

This gate is at lines 28555-28561. `BOOL_40003331` is the explicit unlocked/bypass condition used by
the development/calibration path; normal production operation requires coding match and stored
calibration CRC agreement.

Integrity DTC processing is visible in `FUN_00a35a14()`: RAM/application/checksum and calibration
conditions feed DTCs `0x90`, `0x91`, `0x38`, `0xa3`, and variant-specific `0x9b` (lines
50722-50776).

## Ignition/start inputs and power-state interpretation

### Confirmed ignition-sense inputs

`FUN_00a742fc()` compares two raw analog snapshots, `_DAT_400159b2 >> 4` and
`_DAT_400159ae >> 4`, against the same calibrated threshold `CALBASE+0x8b << 2` (lines
76589-76733). The state machine uses them as independent wake/ignition-sense evidence:

- either input above threshold can leave the initial delay state;
- their transitions drive run and after-run state changes;
- disagreement and timeout take conservative paths.

These are confirmed redundant software ignition/power inputs. Their physical pins and whether one is
KL15, relay feedback, or a second switched supply remain unknown.

`FUN_00a7418c()` controls a logical hold/output flag `DAT_c3f906b1` and calls one of two low-level
output helpers (`FUN_00a0661c()` / `FUN_00a06608()`) (lines 76522-76547). This is the power-hold
boundary used by the state machine, but the connector pin and external relay/transistor are not yet
mapped.

### Crank request versus crank rotation

The application clearly detects crankshaft rotation and synchronization. A distinct driver “start
button” or starter-relay request has not been named. Therefore:

- **confirmed:** crank/cam teeth, engine period and synchronized cylinder phase authorize combustion;
- **unknown:** whether the ECU directly commands the starter solenoid or only observes rotation after
  a body controller commands it;
- **unknown:** the CAN/body signal that represents key-valid/start-authorized state, if one exists in
  this application.

## Crank/cam synchronization and engine-state hierarchy

### Decoder initialization and safe reset

`FUN_00a0c548()` clears `DAT_400033f8`, engine speed, event histories, sync state and all six output
channel states, then initializes crank/cam capture channels in safe/off configurations (lines
28000-28154). This is both startup initialization and the fallback when the decoder loses enough
events.

`FUN_00a0cb4c()` reads the eTPU/event decoder state, crank tooth position and timestamps (lines
28155-28618). If decoder confidence is below two, it calls `FUN_00a0c548()` (lines 28530-28542),
revoking synchronized scheduling rather than extrapolating indefinitely.

### Synchronization predicate

`FUN_00a8fc14()` returns true only when `DAT_400035fc` is state 2 or 3 (lines 89864-89870). Within
the crank/event routine this is the final `cVar5` predicate required to set `DAT_400033f8` (lines
28555-28561). The surrounding decoder validates a repeating six-event pattern, tooth positions and
per-cylinder phase; fault/status bits are monitored by DTC code at lines 53080-53614.

The exact OEM names of synchronization states 2 and 3 are unknown. Structurally they are the two
accepted fully phased states, likely representing alternative cam/phase confirmations.

### Combustion-enable transition

When all gates pass, the routine sets `DAT_400033f8 = 1` and immediately:

- calls `FUN_00a93adc()` for cylinders 0..5 to install ignition timing;
- initializes six ignition output channels;
- calls `inj_set_trigger()` for cylinders 0..5 at six evenly spaced 720-degree positions;
- initializes six injector output channels plus related crank event outputs.

The complete transition is at lines 28555-28614. This is the strongest recovered start-authority
boundary: before it, normal scheduled fuel/spark events are not armed; after it, both are.

`injection()` also checks `DAT_400033f8` before applying the fixed six-cylinder trigger pattern
(lines 50449-50456). Other paths use this flag to select stopped/startup sensor initialization and
fuel/ignition behavior.

### Engine-running qualification

`FUN_00a0ea38()` derives `engine_speed` from `engine_period1`, or forces zero if the period is invalid
(lines 29146-29175). It then manages `DAT_40003428` independently:

- if `DAT_400033f8 == 0`, running is cleared and stopped sensor/history values are initialized;
- if period falls below the calibrated run-on threshold at `CALBASE+0x12a` and a debounce timer is
  zero, running becomes 1;
- if period exceeds the calibrated run-off threshold at `CALBASE+0x12c`, running becomes 0.

The stopped initialization and hysteretic run-state transition are at lines 29196-29240.

Therefore the visible hierarchy is:

```text
valid application + calibration/coding
             |
valid crank/cam decoder state (2 or 3)
             |
DAT_400033f8 = combustion scheduling enabled
             |
engine period crosses calibrated run threshold
             |
DAT_40003428 = engine running
```

## Fuel and spark start authorization

### Confirmed common enable

Fuel and spark share the synchronization/integrity boundary because the transition that sets
`DAT_400033f8` initializes both output groups in one atomic code path (lines 28555-28614).

`injection()` calculates bank pulses continuously but applies injection enable and minimum-output
substitution separately (lines 50436-50448). Rev-limit and other protection can also set injection
cut flags independently (lines 50410-50435). Thus synchronized start permission is necessary, not
sufficient, for every cylinder to receive fuel.

Ignition values are formed per cylinder by the timing aggregator and scheduled through
`FUN_00a93adc()` (lines 91617-91625). The crank start transition explicitly calls it for all six
cylinders before enabling the ignition channel group.

### No-start and start-abort causes confirmed in this pass

- invalid RSA/application image prevents normal boot acceptance;
- RAM/application/calibration integrity faults are recorded and calibration/coding mismatch blocks
  `DAT_400033f8` unless the development unlock is active;
- decoder state outside 2/3 blocks the combustion-enable transition;
- insufficient/invalid crank events call the decoder reset path;
- per-cylinder event/cam-pattern failures accumulate DTC/status bits and can suppress affected
  cylinder state;
- injection global-enable, rev-limit and severe powertrain fault paths can withhold fuel after sync.

### Still unknown

- a discrete immobilizer challenge/response;
- key transponder or body-controller authorization signal;
- clutch/neutral/brake interlocks for starter engagement;
- starter motor thermal/time-out logic;
- an explicitly identified fuel-pump prime/relay output and rail-pressure-ready prerequisite.

Absence of named evidence is not evidence these functions are absent from the vehicle. They may be
implemented by a body/security controller, embedded in anonymous CAN state, or handled by external
hardware.

## Immobilizer, coding, and CAN authorization

The bootloader RSA key, coding-block CRC, model-name check and calibration unlock should not be
conflated with immobilizer authorization:

- RSA verifies firmware provenance;
- coding CRC/model name verifies configuration compatibility;
- `BOOL_40003331` / `ecu_unlocked` is a calibration-development bypass;
- none directly proves a vehicle-key challenge or rolling-code exchange.

The application has extensive CAN receive code and many anonymous state machines, but this pass did
not find a signal whose dataflow ends directly in the `DAT_400033f8` start gate as an immobilizer
boolean. `FUN_00a8fc14()`, the final predicate in that gate, is a local crank/cam synchronization
state test, not CAN authorization.

Conclusion: **vehicle immobilizer/CAN start authorization remains unknown in the Emira export.** A
future pass should trace all writers of shared start/fault masks and correlate CAN message updates
while capturing an authorized and unauthorized crank attempt.

## Ignition-off and after-run state machine

`FUN_00a742fc()` is the top-level power-state machine (lines 76589-76733). The current state is
`DAT_40003940`, exposed by `FUN_00a7415c()`.

| State | Confirmed behavior |
|---:|---|
| 0 | initialize wake delay |
| 1 | wait/debounce redundant ignition inputs; choose wake or release |
| 2 | pre-run / ignition transition handling and hold control |
| 3 | run state; engine-active flag `DAT_4000377c.0` set |
| 4 | engine-off after-run hold with calibrated timers |
| 5 | final persistent write and external-power release |

### Entering run

`FUN_00a7426c()` sets `DAT_c3f906bc`, sets `DAT_4000377c.0`, optionally asserts the power-hold
output, and enters state 3 (lines 76563-76579). State 3 remains until the primary ignition input
falls below its threshold; it then clears the engine-active bit, computes after-run timers and
enters state 4 (lines 76647-76671).

### After-run duration and work

On the state 3-to-4 transition, the code calls `FUN_00a2cadc()` and computes `DAT_40009204` from an
8x8 `load_` / `coolant_temp_` table at `CALBASE+0x1836`, scaled by 5000. A second calibrated timer
`DAT_40009200` comes from `CALBASE+0xec6e` (lines 76655-76670).

This is confirmed load/coolant-dependent post-key-off residence time. The precise purpose of each
timer is not named. Candidate uses include thermal management, emissions cleanup, throttle/VVT safe
parking, network communication or learned-data stabilization.

State 4 continues to hold power while either timer or registered after-run requests remain active.
`DAT_4000920a` is an after-run request bitmap manipulated by `FUN_00a74168()` and
`FUN_00a74178()` (lines 76497-76514). For example, `ignition___()` registers/clears a request around
its cold/start protection work (lines 32492-32508).

The state machine exits after-run when ignition remains off, engine speed is zero, request/timer
conditions have completed, or a conservative fault/timeout path demands release (lines
76671-76713). If ignition returns, it re-enters run through `FUN_00a7426c()`.

### Thermal after-run boundary

Coolant and load definitely set the hold duration. No fan, charge-cooler pump, or thermostat output
has yet been tied directly to `DAT_40009204`. Therefore this report confirms **thermal-dependent
after-run time**, not a particular fan/pump after-run strategy.

## Persistent/coding writes at shutdown

State 5 performs final housekeeping (lines 76714-76731):

1. reads/clears diagnostic state around DTC `0x8d`;
2. calls `FUN_00a11f0c()` once when stopped/eligible, preparing CRCs and persistent data;
3. re-enables the PowerPC external-interrupt-enable state with
   `WriteExternalEnableImmediate(1)`;
4. if `DAT_40003914` marks coding dirty, calls `eeprom_write_unknown1()`;
5. executes the final release helper `FUN_00a742c4()`.

`eeprom_write_unknown1()` only writes when engine speed is zero and an input-voltage/permission
quantity exceeds its calibrated minimum. It disables external interrupts, erases/programs the
`0x30000` block with a fresh CRC, restores interrupts, and clears the dirty flags (lines
75480-75500). `flash_write_helper_0x30000()` also brackets flash programming with
`WriteExternalEnableImmediate(0/1)` (lines 30763-30806).

The 160-byte block is the coding/VIN/model configuration image. The separate learned/diagnostic image
is checksummed and written by `FUN_00a11f0c()` (lines 30941-30955). Exact wear-leveling and which
individual learned tables are dirty at each shutdown require a separate persistence analysis.

## Actuator-safe shutdown and power release

`FUN_00a742c4()` is the final software shutdown boundary (lines 76581-76587). It:

- clears the run/hold flag `DAT_c3f906bc`;
- calls `FUN_00a7418c(0,0)` to deassert logical power hold and its low-level output;
- returns the power-state machine to state 0;
- calls `FUN_00a4db68()`, `FUN_00a4de90()`, and `FUN_00a083d4()`.

`FUN_00a4db68()` and `FUN_00a4de90()` each program a mirrored bank of low-level output/configuration
registers to calibrated safe/default values, including entries 1, `0x61`, 99 and 100 (lines
64660-64810). Their physical actuators are not named, so they are best described as mirrored
output-driver shutdown/configuration writes rather than specific relays.

Independent actuator paths also have local safe behavior:

- the ETB fault helper commands equal duty to both motor PWM legs;
- `FUN_00a0c548()` disables/reset-safes crank-synchronous fuel and spark channel state;
- injection global disable substitutes minimum pulses and cylinder cut flags can suppress delivery;
- VVT and other PWM strategies contain fault substitutions before the final power release.

The ordering—finish after-run requests, persist state, program safe outputs, then deassert hold—is
consistent with controlled ECU power-down rather than immediate loss of supply.

## Fuel pump and starter authority

Several anonymous single-channel PWM/output strategies exist, but none has yet been proven as the
fuel-pump controller or starter relay by dataflow, pin mux, or diagnostic feedback. In particular,
the existence of pressure-dependent injection and start synchronization does not prove the ECU owns
the pump relay.

Confirmed statements are limited to:

- fuel injection channels remain unarmed until the combustion-enable transition;
- the application observes engine rotation and qualifies running state;
- power hold is locally controlled;
- anonymous output-driver banks are placed in calibrated shutdown states.

Unknown external/body functions include starter request arbitration, clutch/neutral interlock,
immobilizer authorization, starter relay drive, fuel-pump prime, pump relay feedback, and crash-cut
authority.

## Confirmed / inferred / unknown summary

### Confirmed

- boot header/range validation and conditional RSA application-signature policy;
- application RAM, code, calibration, coding and learned-data integrity checking;
- calibration/coding integrity in the combustion-enable predicate;
- crank/cam decoder state 2/3 as the final synchronization predicate;
- simultaneous arming of all six fuel and spark event channels;
- separate hysteretic synchronized and engine-running states;
- redundant ignition/power input thresholds;
- load/coolant-dependent after-run timing and request bitmap;
- stopped-only persistent flash write with CRC;
- explicit output-safe programming and logical power-hold release.

### Inferred

- `_DAT_400159b2` and `_DAT_400159ae` are two forms of ignition/relay-supply sensing;
- `DAT_c3f906b1` and its low-level helper control the main power-hold output;
- sync states 2/3 represent two valid crank/cam phase-lock modes;
- some state-4 work is thermal/emissions after-run beyond mere write completion.

### Unknown

- physical pins for ignition sense and power hold;
- immobilizer or body-controller start-authorization protocol;
- direct starter-relay ownership and interlocks;
- fuel-pump output, prime timing and feedback;
- exact identity of every after-run request bit and thermal actuator;
- whether an external supervisor can independently revoke fuel/spark or power hold;
- all persistent items written on every shutdown versus only when marked dirty.

## Highest-value next steps

1. Trace the SIUL2/pad writers for `_DAT_400159b2`, `_DAT_400159ae`, `DAT_c3f906b1`,
   `FUN_00a0661c()` and `FUN_00a06608()` to build the physical ignition/power-hold map.
2. Trace every writer of `DAT_400035fc` to name synchronization states 0..4 and document exact
   crank/cam acquisition, resynchronization and abort transitions.
3. Follow `DAT_4000920a` bit users and the load/coolant after-run timer consumers to identify fans,
   pumps, throttle parking and emissions work.
4. Compare CAN state during authorized, unauthorized, neutral/clutch-denied and normal crank attempts;
   trace any changed signal into the start/fault masks before labeling an immobilizer gate.
5. Trace all single-channel output PWM/relay functions to pins and diagnostics to identify the fuel
   pump and starter boundaries.
6. Expand the `0x30000` persistent-block layout and dirty flags so coding writes are separated from
   learned fuel, throttle, VVT and diagnostic persistence.
