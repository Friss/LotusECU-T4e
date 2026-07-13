# 8896915220A ROW runtime architecture

Target: 2022 Lotus Emira V6 ROW engine ECU (`8896915220A_ROW`), G6 application on an NXP MPC5777-family controller.

This note describes the runtime structure visible in `emira.c`: application entry, RAM images, the foreground scheduler, interrupt/peripheral coordination, calibration selection, coding flash, and learned-data persistence. Most remaining `FUN_*` names are Ghidra names, so the report separates direct evidence from architectural inference.

## Executive model

The Emira application is a tick-gated, time-sliced foreground controller rather than the Evora's continuously repeated monolithic foreground loop.

1. A hardware interrupt advances `DAT_40003338`, the scheduler tick.
2. `main` runs one of 100 foreground phases (`main_loop_phase = 0..99`) when it observes a new tick.
3. Every phase contains a common fast group; additional groups are interleaved at half-, fifth-, and tenth-phase cadence.
4. One phase-specific slow group is distributed through the 100-slot frame.
5. Independent interrupts service periodic timer sources and engine/event peripherals through a RAM vector table at `0x40000000`.

The observed call pattern is consistent with a 1 ms scheduler tick: the common, alternating, five-way, ten-way, and once-per-frame groups are therefore 1000, 500, 200, 100, and 10 Hz respectively. This rate is high confidence but not proved solely by the truncated interrupt bodies; confirming the incrementing ISR or measuring `DAT_40003338` remains the clean final check.

## Verified startup flow

```text
bootloader application jump: 0x00A00100 (`init`)
  -> disable external interrupts
  -> install low-level exception/flash interface state
  -> clear 0x40000000..0x4003FFFF (256 KiB SRAM)
  -> copy 0x14D4 bytes: 0x00AC5524 -> 0x40001D90
  -> copy 0x009B bytes: around 0x00AC6A18 -> 0x40003284
  -> clear 0x019284 bytes beginning at 0x40003320
  -> establish MSR/runtime state
  -> `main`
     -> runtime constructors (`FUN_00a017c8`)
     -> destructive low-RAM sanity test
     -> scan/copy high-RAM stack sentinel state
     -> fill 0x4003E000..0x4003EDFF with 0x55555555
     -> preliminary globals (`FUN_00a3f91c`)
     -> `init_core_system`
        -> copy calibration 0x00020000..0x0002FFFF to RAM (`CAL_base`)
        -> load/validate coding 0x00030000 -> 0x40009120
        -> calculate calibration CRC and derive `ecu_unlocked`
        -> load/validate learned image 0x00010000 -> 0x4001735C
        -> initialize ADC, timer, serial, CAN, engine-I/O and control subsystems
        -> install interrupt handlers in RAM vector table
     -> `setup_post_init` (output-disable delay)
     -> enter infinite tick-gated 100-phase scheduler
```

Direct source anchors: `init` begins at `emira.c:21054`; its RAM clear and segment setup occupy `emira.c:21075-21229`. `main` begins at `emira.c:22496`; scheduler setup and loop begin at `emira.c:22504-22560`. `init_core_system` begins at `emira.c:23610` and `setup_post_init` at `emira.c:29926`.

### Early RAM initialization

`init` clears exactly `0x800 * 0x80 = 0x40000` bytes starting at `0x40000000`, establishing a 256 KiB internal SRAM image. It then restores two initialized-data regions and clears a large BSS-like region. The decompiler's pre-increment pointer forms make the first copied byte appear one byte before the intuitive source/destination; the stable extents are the byte counts and resulting RAM regions, not the displayed first-byte expression.

The RAM vector table shares the bottom of SRAM. `FUN_00a006fe(handler, vector)` writes a handler pointer to `0x40000000 + vector*4` (`emira.c:21357-21362`). This explains both the startup low-RAM test and the many later peripheral initializers that install handlers there.

## Memory and persistence map

| Address/range | Role | Evidence and confidence |
|---|---|---|
| `0x00010000` | Learned/adaptation and OBD persistence image | explicit load, erase and write of `0x3F94` bytes; high |
| `0x00020000..0x0002FFFF` | 64 KiB calibration flash (`CALROM`) | direct `memmove` source and calibration-selection path; high |
| `0x00030000` | 160-byte coding/VIN/model image | `COD_unknown` load and `flash_write_helper_0x30000`; high |
| `0x00A00100` | main-application entry (`init`) | bootloader note and function address; high |
| approximately `0x00A00000..0x00ACxxxx` | application code, constants and initialized-data image | function/constant addresses and startup copies; high base, exact signed/code boundary unresolved |
| `0x40000000..0x4003FFFF` | 256 KiB internal SRAM | complete startup clear; high |
| `0x40000000...` | RAM interrupt vector table plus low runtime data | `FUN_00a006fe`; high |
| `0x40001D90...` | initialized runtime data | startup `0x14D4`-byte copy; high |
| `0x40003284...` | second small initialized-data region | startup `0x9B`-byte copy; high |
| `0x40003320...` | BSS/transient globals | startup `0x19284`-byte clear; high |
| `0x40009120..0x400091BF` | live 160-byte coding structure | coding load/write and CRC coverage; high |
| `0x4001735C..0x4001B2EF` | learned/adaptation RAM image (`0x3F94` bytes) | load, CRC, reset and save calls; high |
| `0x4002E000..0x4003DFFF` | calibration RAM shadow (`CAL_base`, 64 KiB) | `copy_calrom_to_calbase` and pointer test; high |
| `0x4003E000..0x4003EDFF` | stack/free-RAM watermark | `0x55555555` fill and incremental scan; high |
| `0x4003F000` | configured high-RAM boundary/stack reference | assigned in `main`; medium-high |

## Calibration shadow and runtime selection

`copy_calrom_to_calbase` copies exactly `0x10000` bytes from flash `0x00020000` to `CAL_base`, which other code identifies as `0x4002E000`, then makes `CALBASE_addr` point to the RAM copy (`emira.c:24824-24829`). `select_calbase_addr` can switch the live pointer between flash `0x00020000` and the RAM image (`emira.c:24833-24842`). Thousands of control lookups use `CALBASE_addr + offset`, so this is a genuine movable calibration base, not a one-time unpacking buffer.

`main` continuously compares one byte of RAM calibration against the corresponding `CALROM` byte while otherwise idle and latches mismatch diagnostics (`emira.c:22561-22586`). It also calculates a CRC16 over calibration bytes starting at offset `0x20`, excluding the final two bytes (`init_core_system`, `emira.c:23631`). Four magic calibration bytes at offsets `0xE2`, `0x218`, `0x290`, and `0x337` derive `ecu_unlocked`; this is direct evidence of a calibration-controlled development/unlock state, not proof that every diagnostic write path is protected by it.

The application-side flash helper in this export writes only aligned addresses in `0x00030000..0x0003FFFF` (`flash_write_helper_0x30000`, `emira.c:30763-30810`). Ordinary live-calibration edits therefore affect the RAM shadow; calibration-flash programming appears to belong to the bootloader/reflash protocol.

## Foreground scheduler and task rates

`main` waits for `DAT_40003338` to differ from its last consumed value. Until then it performs background integrity work: application CRC, incremental comparison of RAM calibration against flash, and optional communication service (`emira.c:22561-22618`, `23475-23478`). When the tick changes it executes one `main_loop_phase`, records execution time, and advances the phase modulo 100 (`emira.c:22620-23467`).

The 100 cases are mechanically regular:

| Effective rate if tick = 1 ms | Selection pattern | Wrapper family | Representative work |
|---:|---|---|---|
| 1000 Hz | every phase | `FUN_00a46058` | ADC buffer sample, ETB state machine (currently misnamed as O2), fast engine/sensor and communications services |
| 500 Hz each | alternating phases | `FUN_00a46100` / `FUN_00a461c0` | rev limit and fast actuator/control group; alternate fast group |
| 200 Hz each | five groups rotated across phases mod 5 | `FUN_00a4623c`..`FUN_00a46510` | runtime counters, diagnostics, load/sensor/control calculations, learned-data reset state machine |
| 100 Hz each | ten groups rotated across phases mod 10 | `FUN_00a46588`..`FUN_00a469bc` | injection, engine load, ignition-related calculations, communications and diagnostics |
| 10 Hz | one selected slow wrapper per 100-slot frame (some wrappers recur at several slots) | `FUN_00a46a20` onward | slower CAN/diagnostic service, ignition state, watchdog/timeout accounting, monitor and housekeeping slices |

The wrappers directly profile their own execution using the time-base register (`FUN_00a006f8`) and update per-group maxima. `FUN_00a46038` also checks that the scheduler supplied the expected ordinal and writes `0x5555` sentinels on success (`emira.c:58760-58774`). At the end of slot 99, `main` checks execution headroom, high-water consumption and sentinel health, updates diagnostic flags, and resets the phase to zero (`emira.c:23358-23460`).

The scheduler is intentionally distributed: a complete logical 100 Hz control pass is not one large function but ten phase-specific wrappers. Likewise, the phase-specific slow work is spread across the 100 ms frame to flatten CPU demand. A missed tick is detected (`DAT_40003338 != DAT_40003c64`) and recorded in a per-phase overrun/miss array rather than silently ignored.

### Rate confidence

The relative dividers and 100-slot frame are direct, high-confidence evidence. The absolute 1 ms base is high confidence because wrapper calls explicitly pass `100` to their slow-time accounting, time profiling scales by `0x41A`, and the control groupings match 1000/500/200/100/10 Hz conventions. However, the decompiler exports the installed ISR entry labels as data symbols, so the exact instruction that increments `DAT_40003338` is absent. Until that handler is recovered from assembly or measured, the absolute rates should be described as “consistent with” rather than mathematically proven.

## Interrupts and peripheral coordination

The application installs handlers by vector number into the RAM table. Verified setup sites include:

| Vector | Setup function | Peripheral evidence | Confidence |
|---:|---|---|---|
| `0x12D` | `FUN_00a04364` | software/system timer-style interrupt configuration | medium |
| `0x1D2` | `FUN_00a043b4` | PIT-like channel configured with count `1000` | medium-high periodic source, exact clock division unresolved |
| `0x14B` | initializer near `FUN_00a0aeac` | peripheral event handler | medium-low semantic identity |
| `0xCF` | `FUN_00a16bb0` | engine-I/O/event peripheral register setup | medium |
| `0x175` | `FUN_00a17b98` | timer/input-event channel | medium |
| `0x45` | `FUN_00a17c3c` | timer/input-event channel | medium |
| `0x42` | `FUN_00a6c884` | periodic engine-angle/time monitor source | medium-high |

`init_core_system` initializes two CAN/controller paths, ADC acquisition, serial/diagnostic transport, O2 heaters, engine-speed/event processing, fuel, ignition and other actuator subsystems (`emira.c:23610-23805`). Hardware registers in the `0xC3F9xxxx`, `0xC3FAxxxx`, `0xFFE6xxxx`, `0xFFF48xxx`, and related ranges show direct use of MPC5777 peripherals. Precise module names and interrupt priorities should not be inferred from address resemblance alone without the matching MPC5777 reference-manual map.

The fast wrapper's `adc_sample()` call is non-blocking in context: acquisition peripherals populate state asynchronously, while the scheduled foreground group consumes it. Engine-edge and timer events likewise use installed interrupt handlers, leaving the 1 ms foreground scheduler to perform deterministic state/control updates. Unlike the Evora export, this pass does not yet recover a trustworthy priority table or enough handler bodies to state nesting behavior.

## Learned-data persistence

### Image and validation

The learned image is `0x3F94` bytes at RAM `0x4001735C`, loaded from flash `0x00010000` by `FUN_00a11908` and validated in `FUN_00a12548` (`emira.c:31373 onward`). It contains adaptation and extensive diagnostic/history state, not merely fuel trims.

Two integrity domains are explicit:

- core CRC16 over the first `0x1668` bytes, compared with a stored value near `0x400189C4`;
- full CRC16 over `0x3F90` bytes, compared with a stored value near `0x4001B2EC`;
- stored size markers are checked against `0x1668` and `0x3F94`;
- program identity and a calibration/version marker are also compared.

The recovery policy resembles the Evora design: incompatibility of the core/program/calibration identity invokes broad learning and OBD resets; a failure confined to the extended/full domain preserves more compatible core state while rebuilding affected diagnostic/history areas. The exact semantic boundary at byte `0x1668` still requires field-by-field mapping.

### Save path

`FUN_00a11f0c` records sizes `0x1668` and `0x3F94`, calculates both CRCs, invokes a finalization helper, and calls `FUN_00a11b0c(&DAT_4001735c, 0x3F94)` (`emira.c:30935-30952`). `FUN_00a11b0c` erases the `0x00010000` flash region and writes the image in aligned phrases, padding a partial final phrase with `0xFF` (`emira.c:30678-30720`). Watchdog/service helpers are called around flash operations.

The exact ignition-off call chain to the save-preparation function is not named strongly enough in this export to claim the full after-run timing policy. `ignition___` is scheduled in a slow foreground slice (`FUN_00a46eec`, `emira.c:59576-59603`), and save/write operations are plainly application-controlled, but a one-shot low-voltage threshold and power-hold GPIO sequence comparable to the Evora report have not yet been proved.

## Coding/VIN/model flash path

`COD_unknown` copies 160 bytes from `0x00030000` to `0x40009120`, calculates CRC16 over the first `0x9C` bytes, and compares it with the stored CRC at `0x400091BC` (`emira.c:75396-75455`). If the CRC is bad it fills the structure with `0xFF`, restores the calibrated default model name, and requests a rewrite. If the CRC is valid but the model field is blank, it likewise restores the calibrated model default.

`eeprom_write_unknown1` commits only with `engine_speed == 0` and an additional operating threshold satisfied. It disables external interrupts, erases the `0x30000` region, computes the CRC, writes all 160 bytes (or performs an erase-only request), restores interrupts, and clears update flags (`emira.c:75470-75490`). `flash_write_helper_0x30000` enforces 8-byte alignment and the `0x30000..0x3FFFF` address window.

This coding image is separate from both 64 KiB calibration and learned persistence. That separation is important for tooling: a coding update should not be treated as a calibration-sector edit, and a learned-data reset does not erase coding.

## Stack/free-RAM and runtime integrity

Before subsystem initialization, `main` fills `0xE00` bytes starting at `0x4003E000` with `0x55555555`. `FUN_00a03c24` advances through intact four-word blocks and backs up after disturbed data, producing a high-water/free-RAM monitor (`emira.c:23496 onward`). The end-of-frame scheduler checks this consumption against a limit of `0x80` blocks and latches diagnostics.

Runtime integrity also includes:

- a startup destructive sanity test over selected low-RAM bits;
- an application CRC over the region beginning at `init` with length `0xAA0000` during scheduler idle time;
- continuous bytewise RAM-calibration versus flash comparison;
- wrapper ordering sentinels and missed-tick tracking;
- per-rate-group and per-slow-slice maximum execution-time recording.

These mechanisms make scheduler overrun and accidental RAM calibration divergence observable rather than relying only on a watchdog reset.

## Flash safety and concurrency

- Learned and coding images occupy separate flash regions (`0x10000` and `0x30000`).
- Calibration occupies `0x20000..0x2FFFF` and is copied to RAM; the application coding writer cannot address it.
- Flash paths repeatedly call service/watchdog helpers around erase/program operations.
- The coding write path disables external interrupts for the erase/program transaction.
- Learned-image write code uses the flash driver setup/teardown sequence and aligned 8-byte writes, padding the tail.
- Exact worst-case interrupt blackout and whether driver code runs entirely from cache/RAM are unresolved.

## Confidence and unresolved gaps

High-confidence findings are direct copies, loop bounds, pointer selections, scheduler-case patterns, CRC sizes, vector-table writes, or explicit flash calls. The 256 KiB RAM extent, calibration/coding/learned regions, 100-slot scheduler structure, relative task divisors, and persistence sizes are high confidence.

Remaining gaps:

1. Recover the ISR behind the scheduler tick and prove the absolute 1 ms period and interrupt priority.
2. Decode installed vector numbers against the exact MPC5777 derivative/reference manual and map every event peripheral.
3. Trace the learned-data save request through ignition-off, power-hold and low-voltage decisions; this pass proves the writer but not the complete shutdown policy.
4. Name the major scheduler wrappers' callees and build a control-domain-to-rate matrix comparable to the fully renamed Evora export.
5. Determine whether the background application CRC length `0xAA0000` reflects the intended signed application span or a generated linker constant with excluded regions.
6. Measure phase execution time and missed-tick behavior at high RPM, active diagnostics, heavy CAN load and flash operations.
7. Audit all paths that set the coding update flags and all uses of `ecu_unlocked`; the four-byte calibration magic alone is not an authorization model.

## Marginal value of this pass

This pass establishes that the Emira G6 application is not merely an enlarged Evora loop. It has a 256 KiB RAM model, a movable 64 KiB RAM calibration shadow, a RAM interrupt vector table, and a deliberately balanced 100-slot foreground schedule. It also fixes the three important nonvolatile regions and their live RAM images: learned data at `0x10000`/`0x4001735C`, calibration at `0x20000`/`0x4002E000`, and coding at `0x30000`/`0x40009120`. Those boundaries and task-rate relationships are immediately useful for safe calibration tooling, live-memory logging, scheduler profiling, and avoiding destructive cross-sector writes.
