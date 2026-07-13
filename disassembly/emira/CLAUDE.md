# Lotus Emira G6 ECU Disassembly Reference

## Scope

This directory analyzes the G6 engine ECU firmware used by the 2022 Lotus Emira V6. The checked-in
target is `8896915220A_ROW`, an NXP MPC5777-family PowerPC application. The bootloader transfers
control to application entry `init` at `0x00a00100`.

The primary artifact is a 105,899-line Ghidra C export, not compilable source. Most functions and
globals remain automatically named, so analysis reports distinguish confirmed dataflow from
cross-model inference and unresolved behavior.

## Target artifacts

| Path | Purpose |
|---|---|
| `8896915220A_ROW/emira.c` | Combined bootloader/application decompiler export |
| `8896915220A_ROW/BOOTLOADER_ANALYSIS.md` | Historical boot layout and RSA/SHA-1 analysis; revalidate against a canonical binary before relying on precise coverage claims |
| `8896915220A_ROW/boot_names.txt` | Verified boot flash/CRC/programming names |
| `8896915220A_ROW/boot_crypto_names.txt` | Verified boot framing and seed/key names |
| `8896915220A_ROW/inj_names.txt` | Verified injection-path naming ledger |
| `8896915220A_ROW/analysis/` | Evidence-backed subsystem reports and Evora comparison map |
| `../romraider-defs/8900689277A-emira.xml` | Same-family 64 KiB Emira definition from Donour `master_t6e`; structurally cross-checked, but its calibration ID differs from the analyzed target |

The raw `emirabinary.hex` referenced by the bootloader note, a Ghidra archive/symbol CSV, exact stock
calibration bytes, pinout, CAN captures/DBC, and a second firmware variant are not currently checked
in. The available RomRaider XML identifies calibration `8900689277A`, not analyzed target
`8896915220A_ROW`; that version gap is an important confidence boundary.

## High-confidence architecture

- Application entry: `0x00a00100`.
- Application runtime SRAM: `0x40000000..0x4003ffff` (256 KiB startup clear).
- RAM interrupt-vector table begins at `0x40000000`.
- Learned/diagnostic persistence: flash `0x00010000`, RAM `0x4001735c`, size `0x3f94`.
- Calibration: flash `0x00020000..0x0002ffff`, copied to RAM
  `0x4002e000..0x4003dfff`; consumers use movable `CALBASE_addr` plus a 16-bit block offset.
- Coding/VIN/model structure: flash `0x00030000`, RAM `0x40009120`, size 160 bytes.
- Foreground control uses a tick-gated 100-phase scheduler. Its relative task groups execute every
  1, 2, 5, 10, or 100 phases. A 1 ms base tick—and thus 1000/500/200/100/10 Hz groups—is strongly
  supported but awaits direct recovery or measurement of the tick ISR.
- The application has an OBD Mode 01 table with 59 entries and an indexed DTC model with 258 slots.
- Bootloader programming/security and main-application diagnostics are separate implementations and
  must not be mixed when identifying CAN IDs, sessions, or memory permissions.
- The bootloader accepts programming requests on CAN `0x730` and returns segmented responses on
  `0x630`; `0x7ff` is a secondary accepted request ID whose functional/broadcast role is not proven.
- Boot and application SecurityAccess are independent. Boot unlock authorizes descriptor-bounded
  programming; application unlock has two levels controlling table-registered diagnostics. Neither
  directly sets the calibration-derived application engineering flag.
- Application tooling IDs `0x200..0x202` expose pointer-based live-memory operations without the
  named engineering gate. Their physical DLC/gateway reachability remains unproved and is a priority
  defensive validation item.
- A CRC/sequence/magic-checked synchronous serial link exchanges ETB state and redundant sensor
  values with an external safety participant. Its silicon family is unresolved; no evidence yet
  identifies it specifically as the Evora-style HC08.

## Powertrain findings

- `engine_load___()` calculates modeled cylinder load, normalized load forms, exhaust flow, and OBD
  absolute load; the exact MAF/MAP/fallback source arbitration is not fully named.
- `injection()` builds separate bank pulses from differential pressure/flow, two 20x20 AFR tables,
  corrections/trims, cycle limits, and a hard-rev-limit fuel cut. Six-cylinder trigger phasing is
  explicit.
- Fuel-pressure regulation commands PWM channel `0x4b` from measured pressure difference,
  delivered-fuel demand, feedback, and retained duty learning. Purge is separate on channel `0x56`;
  its estimated air and hydrocarbon contributions are integrated into bank fueling.
- Ignition uses separate manual/IPS 20x20 base maps and produces six per-cylinder event angles. The
  Emira knock cluster has an 11-bin configuration, 128-sample ring, four DSP modes, six-cylinder
  correction arrays, and persistent learned values; its acquisition ISR and physical sensor mapping
  remain unresolved.
- Electronic throttle uses complementary PWM channels `0x14`/`0x15`, stop adaptation, fault fallback,
  idle PID, injector-capacity limiting, rev limiting, and anonymous external limit inputs.
- Four closed-loop VVT outputs use eMIOS channels `0x46`, `0x42`, `0x44`, and `0x43`. Code and
  RomRaider addresses support logical intake (`0x46/0x44`) and exhaust (`0x42/0x43`) target families;
  physical bank assignments remain unresolved.
- A ratio-based six-speed gear estimator and active manual/IPS calibration selection are confirmed.
  ESP/traction/gearbox torque-request CAN signals remain unnamed.

## Calibration status

The calibration block is structurally clear, and Donour's same-family `8900689277A` XML provides 96
top-level objects. A reproducible audit finds at least one direct code-address match for 58 objects
and complete data/axis-address matches for 54. Only eight objects have meaningful `CAL_` symbols in
the decompiler itself. The XML is a strong naming and structure seed, but the ID mismatch, absent
stock bytes, unverified scaling, CRC coverage, and signature packaging prevent an exact
`8896915220A_ROW` tuning-readiness claim.

Treat the block-relative offset as the portable identifier. Do not confuse persistent flash
`0x0002xxxx` addresses with the runtime RAM shadow at `0x4002e000`.

## Analysis index

| Report | Contents |
|---|---|
| `analysis/RUNTIME_ARCHITECTURE_ANALYSIS.md` | Startup, memory, scheduler, interrupts, integrity, learned/coding persistence |
| `analysis/POWERTRAIN_CONTROL_ANALYSIS.md` | Air/load, injection, ignition, throttle/torque, idle, VVT, thermal, transmission, rev limit |
| `analysis/FUEL_AIR_ANALYSIS.md` | Charge-source arbitration, bank lambda control, injector/pressure characterization, fuel-pressure PWM, purge accounting, transients, limits and cuts |
| `analysis/VVT_IDLE_THERMAL_ANALYSIS.md` | Four VVT loops, idle target/feed-forward/PID learning, A/C coordination, cooling PWM, thermal fallbacks and after-run boundaries |
| `analysis/EMISSIONS_DIAGNOSTICS_ANALYSIS.md` | Four O2/heater channels, bank trims, catalyst ratio/readiness, purge/EVAP boundary, misfire interaction and monitor indices |
| `analysis/CAN_DIAGNOSTICS_ANALYSIS.md` | Application CAN evidence, OBD PIDs, DTC model, boot programming services |
| `analysis/CALIBRATION_COVERAGE_ANALYSIS.md` | Calibration bases, integrity, named coverage, anonymous table evidence, tuning-readiness gaps |
| `analysis/CALIBRATION_LOOKUP_INVENTORY.md` | Reproducible inventory of 333 direct lookup calls and 628 literal block offsets; companion CSV/script |
| `analysis/ROMRAIDER_DEFINITION_VALIDATION.md` | Provenance and structural audit of the `8900689277A` XML; companion 96-object crosswalk/script |
| `analysis/BOOTLOADER_PROGRAMMING_ANALYSIS.md` | Corrected boot range/entry, CAN transport, UDS services, seed/key, download/program/CRC, RSA/SHA policy |
| `analysis/OBD_UNLOCK_PATH_ANALYSIS.md` | Consolidated application, bootloader and engineering authorization paths; capability matrix and defensive validation priorities |
| `analysis/BOOT_SECURITY_ACCESS_PATHS.md` | Boot session/SecurityAccess state, retry/delay behavior, service gates, descriptor bounds and RoutineControl erase inconsistency |
| `analysis/APPLICATION_DIAGNOSTIC_ACCESS_PATHS.md` | Application OBD/extended dispatchers, two security levels, resource masks, safe-state gates, cleanup and unresolved CAN route |
| `analysis/ENGINEERING_UNLOCK_PATHS.md` | Calibration-derived engineering flag, `0x40..0x47` raw-memory family, un-gated `0x200..0x202` tooling and persistence boundaries |
| `analysis/SENSOR_ETB_SAFETY_ANALYSIS.md` | ADC/result architecture, redundant pedal/TPS, ETB learning/fallback, external safety companion |
| `analysis/DIAGNOSTIC_MONITOR_FRAMEWORK_ANALYSIS.md` | 258-slot DTC lifecycle, qualification, snapshots, readiness, OBD/extended services, emissions monitors |
| `analysis/DTC_DICTIONARY_RECOVERY_ANALYSIS.md` | Reproducible inventory of 490 DTC submissions and the exact missing-function boundary for external P/U/B/C mapping |
| `analysis/CRANK_CAM_EVENT_ANALYSIS.md` | eTPU crank/cam channels, synchronization, six-cylinder fuel/spark scheduling, VVT capture, misfire handoff |
| `analysis/IGNITION_KNOCK_ANALYSIS.md` | Base timing, 11-bin/128-sample knock DSP, cylinder windows, correction layers, and persistent six-value adaptation |
| `analysis/APPLICATION_CAN_TOPOLOGY_ANALYSIS.md` | Three application FlexCAN instances, descriptor engines, engineering IDs/protocols, and bounded vehicle-signal evidence |
| `analysis/TORQUE_TRANSMISSION_CRUISE_TRACTION_ANALYSIS.md` | Pedal/torque path, manual/IPS coordination, spark/fuel/ETB authority layers, cruise/ESP/traction/launch boundaries |
| `analysis/LEARNED_DATA_PERSISTENCE_ANALYSIS.md` | Learned-image layout, nested CRCs, domain repair, knock/ETB/fuel/idle persistence, DTC snapshots, and shutdown writes |
| `analysis/START_LOCKOUT_AND_ENGINE_SHUTDOWN_ANALYSIS.md` | Integrity/sync combustion gate, running state, after-run, persistent writes, safe output and power-hold release |
| `analysis/REMAINING_CODE_AUDIT.md` | Exact symbol-debt counts, largest anonymous functions, covered domains, and highest-leverage unresolved clusters |
| `analysis/EVORA_TO_EMIRA_ANALYSIS_MAP.md` | Mapping of all 21 Evora GT430 reports to Emira evidence and prioritized backlog |

## Working rules

1. Prefer function addresses over line numbers when carrying results across regenerated C exports.
2. Give every recovered name an evidence trail: callers, callees, data references, and relevant
   calibration offsets.
3. Use Evora names as search hypotheses only. MPC5534 peripherals, HC08 supervision, CAN layouts,
   task rates, tables, and thresholds are not portable to MPC5777 by assumption.
4. Separate active behavior for `8896915220A_ROW` from retained common-family enums/code.
5. Do not publish a calibration definition from lookup shape alone; verify raw bytes, axes, scaling,
   mode selection, integrity, and packaging.
6. Keep bootloader, application, calibration, learned data, and coding flash as separate trust and
   write domains.
