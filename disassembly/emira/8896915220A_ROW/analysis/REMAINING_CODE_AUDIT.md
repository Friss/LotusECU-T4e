# Emira remaining-code audit

Target: 2022 Lotus Emira V6 ROW firmware `8896915220A_ROW`.

## Current recovery level

The checked-in `emira.c` contains 2,166 parsed function definitions. Of those, 2,081 still use a
Ghidra `FUN_*` name; only 85 definitions have any semantic name. In other words, approximately
**96.1% of function definitions remain address-only**, even though the new reports recover useful
behavior for many of them without renaming the export.

There are 2,085 unique `FUN_*` tokens and 7,819 total `FUN_*` references in the file. Total references
must not be reported as the function count; an earlier inventory draft conflated those measures.

The analysis is therefore subsystem-rich but symbol-poor. The highest leverage is no longer another
linear read of 105,899 lines. It is converting the strongest report findings into a primary symbol
export, then using cross-references and the calibration/DTC inventories to attack the remaining large
clusters.

## Largest recovered functions

The approximate line span below runs from one parsed definition to the next. It is a triage measure,
not compiled size.

| Function | Approx. lines | Current interpretation | Recovery status |
|---|---:|---|---|
| `FUN_00a3f9b0` | 1,973 | Large diagnostic-monitor coordinator: 96 `dtc_set_status_()` calls, 47 prerequisite-health queries, 236 CALBASE references | Framework known; individual monitor identities mostly unknown |
| `FUN_00aa149c` | 1,660 | Very large table/state algorithm with 64 CALBASE references and dense pointer arithmetic | High-priority unknown |
| `FUN_00a39150` | 1,260 | Large diagnostic monitor bank: 92 DTC submissions and more than 100 calibration references | Framework known; DTC dictionary required |
| `main` | 1,000 | Tick-gated 100-phase scheduler and background integrity work | Covered |
| `FUN_00a19328` | 962 | Powertrain calculation using ignition/cylinder state and numerous 2D/3D calibration lookups | Partial; likely high torque/combustion leverage |
| `FUN_00a30f20` | 798 | Idle target, compensation, and learned corrections | Covered in powertrain report; should be renamed |
| `FUN_00a825ac` | 774 | Diagnostic lifecycle/record worker | Covered structurally; record fields still need typing |
| `injection` | 754 | Dual-bank mixture, injector characterization, purge accounting, pulse limiting, and six-cylinder scheduling inputs | Covered in the fuel/air report |
| `FUN_00a67a94` | 691 | Large indirect switch/dispatch routine with decompiler control-flow damage | Needs instruction-level recovery |
| `FUN_00a80fe8` | 623 | Diagnostic configuration/lifecycle worker | Covered structurally; configuration tables still anonymous |
| `FUN_00a20b88` | 591 | Four-loop VVT continuation/monitor region | Covered through all four output loops and internal indices 0–11; physical bank mapping remains |
| `FUN_00a1ea40` | 573 | Four-channel closed-loop VVT controller | Covered; physical cam/channel names unknown |
| `FUN_00a29d7c` | 533 | Dependency-heavy diagnostic monitor coordinator with 74 DTC-health queries | Requires external DTC dictionary |
| `FUN_00a897b8` | 529 | Extended diagnostic-service dispatcher/handler with damaged switch recovery | Partial; instruction-level service map needed |
| `FUN_00a37bf8` | 524 | Paired-bank control/learning calculation with dense calibration use | Fuel/lambda domain bounded; exact retained-field schema remains partial |
| `FUN_00a6ec14` | 478 | Misfire/emissions readiness monitor with prerequisite DTC queries | Covered at subsystem level; external DTC identities remain absent |
| `FUN_00a2e63c` | 441 | Six-element/cylinder-oriented control calculation | High-priority combustion/roughness candidate |
| `flexcan_c_rx_40_41_42_43_44_45_46_47` | 433 | Low-ID application CAN command/data family | Structure known; semantics unknown |
| `FUN_00a9f67c` | 422 | Calibration-dense, parameterized powertrain/diagnostic calculation | High-priority unknown |
| `FUN_00aa4f34` | 402 | Runtime-state and diagnostic-health aggregation | Partial |

Large size alone is not proof of importance. `FUN_00a3f9b0` and `FUN_00a39150`, for example, are
large because generated monitor logic is flattened into one body. Their real leverage comes from the
DTC mapping table, not from manually naming hundreds of local booleans.

## Areas now substantially covered

The following are no longer “unknown code” at architectural level, although symbol import and deeper
field naming remain:

- bootloader transport, sessions, seed/key, download/program/CRC, and conditional RSA/SHA policy;
- application startup, memory domains, scheduler, integrity, and persistence writers;
- crank/cam synchronization, eTPU channels, fuel/spark event scheduling, VVT capture, and misfire
  interval handoff;
- modeled load, injection, ignition output, throttle/idle, VVT, gear estimation, and rev limiting;
- fuel-pressure PWM, bank lambda correction, purge air/fuel accounting, transient fueling, four O2
  heaters, catalyst ratio/readiness, cooling PWM, and A/C idle-load coordination;
- ADC result architecture, redundant TPS/pedal processing, ETB safe state, and external safety
  companion communication;
- DTC manager, bounded queue, qualification, lifecycle/history, snapshots, readiness, clear paths,
  standard/extended diagnostic families, and 490 inventoried DTC submission sites;
- 333 direct calibration lookup calls exposing 628 literal block-relative offsets.

## Highest-value unresolved clusters

### 1. Primary data dictionaries

The largest multiplier is data recovery rather than more prose:

- 258 internal DTC configurations and their external P/U/B/C identifiers; the call-site side is now
  covered by 218 literal indices plus 32 dynamic submissions;
- 59 Mode 01 PID rows with current/freeze-frame callbacks;
- calibration-relative symbols derived from the 333-call lookup inventory;
- CAN message-buffer IDs, directions, rates, timeout state, and payload writers/readers;
- primary function/global/type export from the Ghidra project.

These dictionaries will resolve dozens of anonymous monitor and service functions simultaneously.

### 2. Torque, transmission, cruise, and traction boundaries

The dedicated report now recovers pedal-to-demand, manual/IPS selection and shift coordination,
torque/load conversions, fast spark/fuel versus slow ETB authority, and bounded cruise/ESP/launch
evidence. Remaining leverage is in the absent normal-CAN descriptor contents: message IDs, scaling,
validity and external request semantics, plus proof of which retained launch/traction modes are active.

### 3. Knock acquisition and adaptation

The follow-up knock report now recovers the frequency setup, 128-sample ring, four DSP modes,
cylinder-phased windows, event/long-term correction arrays, persistent values, and final ignition
handoff. Remaining leverage is concentrated in the missing acquisition ISR, mode-4 detector callback,
stock mode/threshold bytes, and physical sensor/bank routing. Auxiliary eTPU channels 26/27 still must
not be named as knock triggers without dataflow evidence.

### 4. Network contract

Boot CAN is decoded (`0x730` request, `0x630` response, secondary `0x7ff`). Application engineering
traffic is also bounded: `0x200/0x201/0x202` form a proprietary command channel and `0x40..0x47` an
unlock-gated arbitrary-memory protocol. Normal vehicle IDs, periodic rates, gearbox/ESP contracts,
diagnostic addressing, and signal scaling still require descriptor bytes plus vehicle captures.

### 5. Physical I/O

Software channels are much clearer than connector assignments. Remaining work includes SIUL2 pad
mux, ADC result-to-pin mapping, eMIOS output pads, power-hold/ignition-sense pins, starter/fuel-pump
ownership, VVT bank/cam mapping, and the external ETB safety participant's identity.

### 6. Emissions gaps

Four heater outputs, bank lambda correction, catalyst activity ratios, purge accounting, readiness,
and misfire interaction are now mapped. Full sealed-system EVAP leak detection remains unproved;
exact P030x/P042x/fuel-system codes, remaining readiness bits, and individual purge monitor slots
still require the diagnostic dictionary and deeper state-to-submission cross-references.

## Decompiler hazards that deserve instruction-level recovery

Prioritize instruction review where the C export shows:

- failed or truncated switch recovery, especially `FUN_00a67a94` and `FUN_00a897b8`;
- indirect function pointers in diagnostic/PID/DTC tables;
- overlapping `DAT_*` globals that change signedness or apparent width;
- 24-bit eTPU parameter RAM accesses represented as unaligned ordinary pointers;
- computed calibration bases omitted by the direct lookup inventory;
- boot descriptor/signature symbols whose absolute data address was lost;
- functions with implausible dropped arguments, particularly security and table helpers.

## Recommended next sequence

1. Recover or add a canonical binary and replayable Ghidra project with hashes.
2. Export primary functions, globals, types, DTC records, PID rows, and memory blocks.
3. Import the high-confidence names already established by these reports, including correcting the
   ETB function currently misnamed as `o2_sensor_state_machine___()`.
4. Decode application CAN IDs/signals and trace external torque limits into throttle/ignition/fuel.
5. Recover the missing knock ISR/detector callback and validate the active mode and thresholds from
   stock calibration bytes.
6. Resolve normal vehicle CAN descriptors and join their torque/transmission/traction signals to the
   recovered authority paths.
7. Map physical I/O and validate critical behavior against captures or bench stimulus.
8. Only after raw calibration bytes are present, turn candidate lookup offsets into a typed tuning
   definition and verify CRC/signature packaging.

## Practical conclusion

The Emira export is now navigable at the architectural level, but still not normalized for sustained
reverse engineering. The next major gain will come from making the recovered knowledge executable:
symbol imports and machine-readable DTC/PID/CAN/calibration tables. Until that happens, every new pass
will continue paying the tax of 96% address-only function names.
