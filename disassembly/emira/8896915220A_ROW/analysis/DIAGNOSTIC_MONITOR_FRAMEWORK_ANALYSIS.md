# Emira diagnostic monitor framework and emissions analysis

Target: 2022 Lotus Emira V6 G6 ECU, firmware `8896915220A_ROW`, MPC5777.

Primary evidence: `emira.c`. Addresses below come from recovered `FUN_00...` names; line references
refer to the current checked-in export. This report distinguishes three evidence levels:

- **Confirmed** means the behavior is explicit in the decompiled body and its call sites.
- **Inferred** means structure and data flow strongly identify the role, but a table, bit meaning, or
  physical signal is still unnamed.
- **Unknown** means the current export does not safely support the conclusion.

## Executive model

The application has a shared diagnostic manager for **258 internal DTC slots** (`0x000..0x101`).
Subsystem monitors do not directly manipulate the external DTC records. They submit an internal index
and a monitor result to `dtc_set_status_()` at `0x00a86e8c` (source lines 83879–83888). When the
diagnostic manager is active, the report is placed in a 100-entry ring. A periodic consumer processes at
most ten queued reports per invocation, applies per-DTC qualification and status transitions, updates
history/occurrence records, captures configured data, and notifies persistence/change tracking.

The resulting layers are:

```text
sensor/controller monitor
  -> dtc_set_status_(internal index, result)
  -> 100-entry report queue
  -> configured monitor/result transformation
  -> current 26-byte DTC lifecycle record
  -> occurrence / aging / confirmation handling
  -> snapshot and extended-data capture callbacks
  -> OBD and UDS-style service filters
  -> change notification / nonvolatile mirror
```

The internal number passed to `dtc_set_status_()` is **not the external P-code or U-code**. The
configuration accessor at `0x00a7f738` returns a 17-byte record for an internal slot, while the accessor
at `0x00a7f77c` returns a separate 32-bit external identifier. The checked-in C export does not expose a
human-readable index-to-DTC dictionary, so this report intentionally does not assign P-codes from index
values alone. `DTC_DICTIONARY_RECOVERY_ANALYSIS.md` and `dtc_call_inventory.csv` now inventory 490
producer calls and identify the missing production constructor at `0x00a7662c` as the exact recovery
boundary.

## 1. Manager activation and session state

`get_diag_session_state()` at `0x00a7f648` returns `DAT_4000b990` (lines 78679–78684).
`FUN_00a7f9a0()` at `0x00a7f9a0` is its setter (lines 79014–79019). Three observed values have distinct
roles:

| Value | Confirmed behavior | Interpretation |
|---|---|---|
| `0` | `FUN_00a86a48()` performs full manager/config initialization only in this state | Uninitialized/reset state |
| `1` | Startup sequencing passes through this value before the operational transition | Configuration/intermediate state, inferred |
| `2` | DTC submission, DTC queries, snapshot access, clear operations, and OBD status APIs are enabled only in this state | Operational diagnostic state |

Application startup invokes `FUN_00a86a48()` and then `FUN_00a8699c()` (lines 23751 and 23769).
`FUN_00a86a48()` builds the configuration and clears transient manager storage while state is zero
(lines 83682–83700). `FUN_00a8699c()` validates recovered records and installs the operational state
(lines 83645–83679). This is an application-internal diagnostic-manager state, not proof of a tester's
UDS DiagnosticSessionControl value.

## 2. DTC namespace and record families

### 2.1 Internal configuration and live state

The strongest address/stride evidence comes from the accessors at lines 78686–78922:

| Base/accessor | Count and stride | Confirmed role | Important fields or behavior |
|---|---:|---|---|
| `0x4000d013`, `FUN_00a7f738(index)` | 258 × 17 bytes | Per-slot configuration | Enable flag at the first byte; internal index/external mapping and callback pointers are consumed throughout the manager |
| `0x40009b50`, `FUN_00a7f6c8(index)` | 258 × 26 bytes | Current DTC lifecycle records | Internal index, qualification accumulator, status bytes, occurrence/aging counters, severity-like values, and extended values |
| `0x4000b585`, `FUN_00a7f758(index)` | 258 × 4 bytes | Compact per-slot policy/configuration | Used for thresholds and filtering |
| `0x4000e138`, `FUN_00a7f768(index)` | 258 × 3 bytes | External group/code and policy flags | Queried by clear filtering and snapshot policy |
| `0x40010d1b`, `FUN_00a7f77c(index)` | 258 × 4 bytes | External diagnostic identifier | Returned to service enumeration; compared against requested 24-bit DTC groups |
| `0x40019490`, `FUN_00a7f88c(index)` | 258 × 22 bytes | Mirrored auxiliary/extended record | Updated with lifecycle data and exposed by diagnostic service builders |

`FUN_00a8001c()` at `0x00a8001c` initializes all 258 slots and the supporting tables
(lines 79409–79443). `FUN_00a7fe74()` at `0x00a7fe74` clears the runtime record sets, including all
258 lifecycle and auxiliary entries, 21 snapshot records, a second 21-record bank, and ten compact event
records (lines 79277–79341).

The decompiler's small `struct_dtc_state` at lines 600–606 (`dtc_state`, `fail_counter`,
`pass_counter`) is consistent with the architecture, but it is not applied to the larger 26-byte manager
record. It should not be treated as the complete DTC schema.

### 2.2 External DTC identity

Enumeration helpers such as `FUN_00a7561c()` (`0x00a7561c`, lines 77599–77631) walk live slots,
apply active/status filters, and return the 32-bit identifier from `0x40010d1b` plus the current status
byte. `FUN_00a760cc()` and `FUN_00a76164()` (lines 78000–78046) match a request against either an exact
identifier or special groups `0xffffff` and `0xfff000`.

Therefore:

- **Confirmed:** indices such as `0xbe`, `0x2a`, or `0xeb` are manager slots.
- **Confirmed:** the service-facing DTC code is stored separately.
- **Unknown:** the actual P/U/B/C code attached to each internal index; it requires exporting the
  configuration/mapping table from the binary or Ghidra project.

## 3. Submission queue and execution budget

`dtc_set_status_()` calls `dtc_queue_add_()` only when manager state is 2. `dtc_queue_add_()`
(renamed symbol; lines 79055–79081) implements:

- index validation: only `0..0x101` is accepted;
- a 100-entry ring at `0x4000bb9f`, each entry holding a 16-bit index and an 8-bit result;
- producer index `DAT_4000923a`, wrapping modulo 100;
- occupancy accounting in `DAT_40011382` with a high-water statistic;
- an overflow path that sets an internal manager error through `FUN_00a7f9c8(1, 4, 0)`.

The consumer is in `FUN_00a80d58()` at `0x00a80d58` (lines 79919–79966). It drains no more than ten
entries per call, invokes `FUN_00a84cec(index, result)` for each, clears the consumed slot to `0xffff`,
advances the consumer index modulo 100, and decrements occupancy. This bounds diagnostic processing work
without dropping the producer/consumer separation.

The scheduler calls `FUN_00a80d58(1)` at the start of `FUN_00a46058()` (lines 58785–58792), in the same
worker group as ADC acquisition, ETB state handling, and several monitor calculations. The argument and
exact period are not yet named; the ten-record budget is confirmed, while the wall-clock queue latency is
unknown until the scheduler rate is finalized.

## 4. Result meanings and qualification

### 4.1 Producer-facing result values

Across monitor call sites, result `1` is used on the non-failing branch and result `2` on the failing
branch. The central worker confirms this mechanically:

- `FUN_00a8626c()` at `0x00a8626c` tests bit `0x02` as the failure path (lines 83248–83282).
- It tests bit `0x01` as the pass path (lines 83283–83294).
- Some monitors submit `0x04` or `0x08`; these enter alternate qualification modes through
  `FUN_00a7f084()` and must not be labeled simply pass/fail.

| Submitted value | Supported meaning |
|---:|---|
| `0x01` | pass/not currently failed — confirmed |
| `0x02` | fail — confirmed |
| `0x04` | decrement/ramp qualification mode — confirmed mechanics, semantic name unknown |
| `0x08` | increment/ramp qualification mode — confirmed mechanics, semantic name unknown |
| high bit | forces/marks a threshold outcome in `FUN_00a7f084()` — exact producer contract unknown |

### 4.2 Configured qualification

`FUN_00a7f084()` at `0x00a7f084` (lines 78316–78392) transforms the producer result using a per-DTC
configuration object and a signed accumulator at lifecycle-record offset `+2`:

- mode 1 loads one configured endpoint;
- mode 2 loads the other endpoint;
- mode 4 ramps downward by a configured step with clamps;
- mode 8 ramps upward by a configured step with clamps;
- crossing the configured fail/pass thresholds returns qualified fail/pass;
- a further threshold sets the high result bit and a status subfield;
- a normalized 0–255 progress value and its maxima are retained in the record.

This establishes a general debounce/qualification facility rather than requiring every subsystem to
implement its own counter. Many producer functions also have local enable or prequalification counters;
those are upstream monitor logic, not the shared lifecycle itself.

### 4.3 Shared lifecycle effects

On qualified failure, `FUN_00a8626c()`:

- marks the monitor as failed in its small source-state byte;
- performs first-failure handling through `FUN_00a84e54()`;
- increments saturating occurrence counters;
- sets a group of status bits with `record_status = (status & 0xaf) | 0x27`;
- resets several pass/aging counters;
- propagates related-status state through `FUN_00a85d0c()`;
- invokes `FUN_00aac64c()` for confirmation/dependency effects;
- copies the updated 26-byte record to a change/publication buffer.

On qualified pass, it clears current-failure-related bits, increments a pass/aging counter once per
cycle, and keeps history-related state where policy requires. Functions `FUN_00aac5d8()` and
`FUN_00aac830()` compare occurrence counts with per-DTC or group thresholds before confirmation-related
bits and records are promoted (lines 102803–102891).

The status byte is strongly shaped like a standardized DTC status mask, but this export does not name
the individual bits. Exact labels such as `testFailedThisOperationCycle`, `pendingDTC`, or
`confirmedDTC` remain **inferred** until the `0x19` response builder and configuration table are decoded
side by side. The transition mechanics above are confirmed.

## 5. Snapshot, freeze-frame, and event records

There are several distinct capture families; collapsing them all into “freeze frame” would lose useful
boundaries.

### 5.1 OBD Mode 02 freeze-frame bank

The bank at `0x4001aabc` holds 21 records of `0x34` bytes. `FUN_00a75a18()`
(`0x00a75a18`, lines 77441–77483) locates the primary record; `FUN_00a75ac4()`
(`0x00a75ac4`, lines 77486–77528) retrieves a requested PID from it using the Mode 01 handler metadata;
and `FUN_00a75568()` (`0x00a75568`, lines 77509–77553) retrieves record data by DTC/frame selector.

The Mode 02 service worker at `0x00a66c90` accepts PID/frame pairs and calls the second callback in the
59-entry PID table (lines 67760–67867). This makes the identification as OBD freeze-frame storage
**confirmed**. What is not yet known is the replacement priority among the 21 slots.

### 5.2 Callback-built DTC snapshots

`FUN_00a84860()` at `0x00a84860` (lines 81735–81867) constructs a `0x34`-byte record by invoking
configured capture callbacks. It supports two capture types and bounds the data payload to 45 bytes.
`FUN_00a85dbc()` then inserts a completed record into the 21-slot banks through
`FUN_00a85e38()`/`FUN_00a860d0()` (lines 82997–83015). The lifecycle worker requests this capture on
first/current failure and, where configured, for an alternate occurrence/history context
(lines 82058–82092).

This is richer than a fixed snapshot struct: each DTC configuration selects data-provider callbacks and
the manager serializes their results. Exact PID/DID membership is stored in pointer tables not named in
the C export.

### 5.3 Compact occurrence/event records

The manager also maintains:

- 258 four-byte compact records at `0x40018b7e`;
- 258 22-byte auxiliary records at `0x40019490`;
- ten 3-byte records at `0x40018b60`;
- ten 10-byte records at `0x4001b004`.

`FUN_00a76004()` at `0x00a76004` allocates a ten-entry event record on qualifying failure and captures
time/distance-like values and state flags (lines 77949–77992). `FUN_00a76224()` validates such a record
against calibrated elapsed tolerances and a key-cycle discriminator (lines 78073–78114). Physical units
and the exact service names are inferred; the existence of bounded occurrence/event tracking is
confirmed.

## 6. Readiness and MIL coupling

### 6.1 Mode 01 PID 01 payload

`FUN_00a75b74()` at `0x00a75b74` (lines 77531–77568) builds a four-byte payload with the standard
shape of OBD Mode 01 PID 01:

- byte 0: active-DTC count, capped at `0x7f`, with bit 7 set from a manager/MIL state;
- bytes 1 and 2: calibration-derived supported/availability masks;
- byte 3: runtime readiness byte `DAT_4001b13f`.

The callback's exact table row is indirect, but the payload layout makes the PID 01 identification
high-confidence. `FUN_00a7574c()` counts qualifying DTCs by walking all 258 slots
(lines 77422–77439).

### 6.2 Readiness lifecycle

`DAT_4001b13f` initializes from calibration offset `0x4407` (line 31174). Individual monitor families
clear bits as their tests complete or become satisfied:

| Runtime bit cleared | Evidence site | Associated family |
|---:|---|---|
| `0x01` | `catalyst_monitor_enable_check_()`, line 35009 | Catalyst/downstream O2 family — confirmed by function role |
| `0x80` | `FUN_00a20b88()`, line 39868 | Exact monitor family unknown |
| `0x20` | `FUN_00a2e124()`-area, line 51662 | Exact monitor family unknown |
| `0x40` | large monitor dispatcher, line 53356 | Exact monitor family unknown |
| `0x04` | `FUN_00a6ec14()`, line 73689 | Exact monitor family unknown |

The manager also maintains a delayed global readiness/aging condition. `FUN_00a801d4()` decrements
three calibration-loaded counters (`0x4554..0x4558`) under engine/cycle/temperature conditions and calls
`FUN_00a85b9c(1)` only when the gates expire (lines 79627–79653). This flag is consumed by event and
confirmation logic. It should be described as a global diagnostic aging/readiness gate, not assigned to
a specific SAE readiness monitor without further table recovery.

### 6.3 Catalyst gating uses diagnostic health, not only engine conditions

`catalyst_monitor_enable_check_()` reads current-health state for dozens of internal DTC indices through
`FUN_00a86c90()` before enabling either bank's monitor (lines 34808–34892). It also checks coolant,
load, RPM, run state, air/load windows, bank sensor availability, and other controller flags. This proves
the readiness monitor is dependency-aware: a failed prerequisite monitor suppresses catalyst execution
rather than automatically marking catalyst failure.

## 7. Clear, reset, and persistence coupling

### 7.1 Clear services

The shared clear front end is `FUN_00a84bc8()` at `0x00a84bc8` (lines 81980–82031), reached through
`FUN_00a754a0()`. It validates a requested group, supports special all/group identifiers `0xffffff` and
`0xfff000`, clears the report queue before a broad clear, and applies an asynchronous timeout for the
broad operation.

`FUN_00a76454()` at `0x00a76454` (lines 78230–78284) performs the per-slot clear. For each matching
external identifier it resets occurrence, auxiliary, snapshot, and lifecycle state, calls a configured
clear callback if present, and emits a change notification if the status changed.

Two diagnostic transports use this same core:

- Standard OBD Mode 04 worker `FUN_00a67028()` calls clear-all with `0xffffff` (lines 67909–67928).
- The extended service dispatcher maps service `0x14` to `FUN_00a890e0()`, which validates a requested
  24-bit DTC group and calls the same clear front end (dispatch lines 69389–69405; handler lines
  85289–85327).

`FUN_00a76434()` separately clears ten snapshot-selection slots (lines 78296–78308). Full manager reset
`FUN_00aac13c()` at `0x00aac13c` clears all runtime/history banks (lines 102519–102576); this is stronger
than an ordinary tester clear and is used by initialization/reset paths.

### 7.2 Persistence evidence and limits

Persistence coupling is **confirmed at the record-manager boundary but not yet mapped to flash blocks**:

- `FUN_00a858cc(1)` validates recovered compact, auxiliary, snapshot, and event arrays after startup and
  repairs invalid slot indices (lines 82669–82714).
- `FUN_00aacd70()` is called whenever a lifecycle status byte changes; `FUN_00aacd58()` is called for
  category/group transitions. Their bodies feed the manager's change/event infrastructure.
- The initialization sequence calls manager recovery after broader ECU nonvolatile initialization
  (lines 23751–23770).

The exact nonvolatile source addresses, checksums, save cadence, ignition-off ordering, and whether every
snapshot family persists are **unknown**. They require tracing the callers behind the change-notification
functions into the EEPROM/flash service layer. RAM addresses alone do not establish persistence.

## 8. Provable application diagnostic services

### 8.1 Standard OBD service family

The normal dispatcher switches directly on service values `1..10` (lines 68699–68774 and
70142–70174). It implements all standard powertrain OBD modes except the obsolete oxygen-sensor-test
Mode 05:

| Service | Worker | Confirmed behavior |
|---:|---|---|
| `0x01` | `FUN_00a66a60` | Current-data PID requests through the primary callback in the 59-entry table |
| `0x02` | `FUN_00a66c90` | Freeze-frame PID/frame requests through the secondary callback |
| `0x03` | `FUN_00a66f38` | Enumerates stored/confirmed DTC identifiers under one status filter |
| `0x04` | `FUN_00a67028` | Clears all emissions-related diagnostic information through the shared clear core |
| `0x06` | `FUN_00a670d8` | On-board monitor test results through a separate handler table |
| `0x07` | `FUN_00a672e4` | Enumerates DTC identifiers under a second status filter |
| `0x08` | `FUN_00a673fc` | Control-operation service with supported-test bitmap and engine-stopped gating for one request |
| `0x09` | `FUN_00a67704` | Vehicle-information service family; detailed InfoType map remains unnamed |
| `0x0a` | `FUN_00a6695c` | Enumerates permanent-style DTCs under a third filter |

The current-data registry is `obd_ii_handlers_mode01[59]` at source line 8350. The binary-search lookup
is `obd_pid_lookup_routine___()` at `0x00a87080` (lines 83927–83957). Confirmed named handlers include
PID `0x02`, coolant `0x05`, RPM `0x0c`, throttle `0x11`, O2 presence `0x13`, load `0x43`, commanded
lambda `0x44`, ambient air `0x46`, and additional PIDs `0x63` and `0x77` (lines 84192–84996). Supported
PID bitmaps are generated from the registry by `FUN_00a88ca4()` and `FUN_00a88cf4()`
(lines 85061–85117).

### 8.2 Extended/UDS-style services

A separate dispatcher handles at least `0x10`, `0x11`, `0x14`, `0x19`, and `0x22` explicitly
(lines 69378–69411). This proves session control, ECU reset, clear-DTC, read-DTC-information, and
read-data-by-identifier families exist. Other services continue below the recovered switch, but this
report does not claim a complete UDS service map. The transport also produces standard negative-response
style codes (`0x11`, `0x13`, `0x22`, `0x31`, `0x72`, `0x7f`, `0x78`-like pending behavior), though exact
transport/session naming awaits the dedicated CAN/diagnostics pass.

## 9. Emissions monitor families

### 9.1 Oxygen sensing and heater control — confirmed

`o2_heater_pwm_init_()` (renamed symbol; declaration line 63373) initializes four eMIOS PWM channels `0x51..0x54`, four
heater-control structures, and four 300-count timers (lines 63373–63403). `FUN_00a4c3bc()` is the common
heater state/control worker; it carries per-heater state, measured/filtered values, timers, electrical
limits, and output computation (beginning line 63407). Exact bank/upstream/downstream ordering of the
four structures is not yet proven.

The symbol `o2_sensor_state_machine___()` at lines 42864–43181 is incorrectly named in the current
export. Independent dataflow through TPS endpoints, `idle_pid___()`, paired ETB PWM channels, stop
learning, and neutral-output fallback proves that it is the electronic-throttle state machine. It is
not used here as oxygen-sensor evidence; see `SENSOR_ETB_SAFETY_ANALYSIS.md`.

`FUN_00a176ac()` at `0x00a176ac` submits five electrical/activity-related DTC slots
`0xb9..0xbe` based on an O2 status bitmap and calibration enables (lines 34501–34575). The precise sensor
and failure-mode mapping is unknown until the DTC table is exported.

### 9.2 Catalyst/downstream-sensor efficiency — confirmed core, partial outcomes

`catalyst_monitor_enable_check_()` (renamed symbol; declaration line 34693) performs separate bank enable
logic, checks a large prerequisite-DTC set, selects calibration by transmission/variant, computes
temperature/load-dependent thresholds, and accumulates downstream/upstream sensor activity into
bank-specific ratios. It maintains maxima/history and clears the catalyst readiness bit after the
required status conditions are satisfied (through line 35009).

Confirmed observations:

- bank 1 and bank 2 are handled independently;
- monitor enable requires valid prerequisite diagnostics and a calibrated coolant/load/RPM/run window;
- sensor activity is accumulated in eight-element windows per bank;
- a ratio scaled by 1000 is compared with calibration thresholds;
- additional DTC-status queries at indices `0xeb` and `0xec` gate readiness completion.

The final external DTC identities, pass-count policy, and whether every ratio represents catalyst
oxygen-storage efficiency versus downstream-sensor response are **partial**. Index values alone are not
enough to label P0420/P0430.

### 9.3 Fuel-system and lambda monitoring — partial

The Mode 01 registry exposes current fuel-system state, fuel trims, commanded lambda (`0x44`), O2 data,
load, temperature, and exhaust-flow-related values. The renamed PID `0x44` handler at lines 84632–84654 derives
the standardized `2/65536` representation from the injection lambda path (lines 84632–84654).

Many monitor families query O2/catalyst prerequisite slots before submitting their own results, and
`injection()` supplies short- and long-term corrections to the control path. However, the complete
fuel-trim monitor-to-DTC dictionary, rich/lean thresholds, bank mapping, and readiness ownership are not
named in the export. They remain **inferred/partial** rather than confirmed P017x assignments.

### 9.4 Misfire monitoring — structural evidence, semantic mapping incomplete

The diagnostic manager supports occurrence-sensitive records, engine-speed/load snapshot callbacks,
per-event capture, and readiness clearing from an unnamed monitor cluster. Crank/event processing and
large per-cylinder state arrays are present elsewhere in `emira.c`, and several table-driven monitor
families submit DTC indices dynamically (for example `FUN_00a9cde0()` at `0x00a9cde0`, lines
94292–94696).

What is **not** yet proven in this pass is which dynamic index table corresponds to P0300/P0301–P0306,
the crank-acceleration statistic, catalyst-damage threshold, or cylinder-cut response. Those require
cross-tracing the crank/eTPU analysis and exporting the DTC mapping tables. It is safe to say the shared
manager can represent per-cylinder and severity-dependent faults; it is not yet safe to label a specific
anonymous cluster as the complete misfire monitor.

### 9.5 EVAP/purge monitoring — unknown boundary

No stable `evap`, `purge`, `canister`, `tank`, or `leak` semantic names survive in this decompilation.
Anonymous state machines and calibration regions likely include purge control and evaporative monitors,
but this report found no evidence strong enough to separate EVAP leak detection from unrelated pressure,
airflow, or actuator diagnostics. EVAP monitor presence is plausible for the ROW application and Mode 01
readiness model, but the following remain **unknown**:

- purge and vent output ownership;
- tank-pressure sensor acquisition;
- purge-flow versus leak-size state machines;
- EVAP readiness bit;
- attached DTC indices/codes and protective fallbacks.

The next pass should start from output pin/peripheral writers and DTC configuration callbacks, not from
ported Evora names.

## 10. Confirmed, inferred, and unknown boundary summary

### Confirmed

- 258-slot internal DTC namespace with separate external identifiers.
- Operational manager gating, 100-entry report ring, and ten-report consumer budget.
- Producer result `1 = pass`, `2 = fail`, plus shared ramp/threshold qualification.
- Shared lifecycle/history transitions, occurrence counting, snapshots, and change notification.
- 21-record Mode 02/callback-built snapshot bank and additional compact event/history records.
- Mode 01–04 and 06–10 dispatch, 59-entry Mode 01 PID table, plus extended services `0x10`, `0x11`,
  `0x14`, `0x19`, and `0x22`.
- Four O2 heater PWM channels, O2 electrical/activity diagnostic state, catalyst prerequisite gating,
  and banked activity-ratio processing.
- Standard OBD-style active-DTC/MIL/readiness payload and per-family readiness-bit clearing.

### Inferred

- Exact standardized names for individual lifecycle status bits.
- Which auxiliary record family corresponds to each UDS snapshot/extended-data record number.
- Meanings and physical units of compact occurrence/event fields.
- Final bank/upstream/downstream order of O2 heater structures.
- Fuel trim, misfire, and some readiness-family ownership in unnamed monitor clusters.

### Unknown

- Full internal-index-to-P/U/B/C DTC dictionary and calibration enable meanings.
- Complete `0x19` subfunction map and every extended diagnostic service.
- Nonvolatile block layout, checksum/version policy, and save timing for diagnostic records.
- EVAP/purge/vent/tank-pressure state machines and their DTCs.
- Exact misfire statistic, P030x mapping, and catalyst-damage intervention.
- Physical O2 heater/sensor channel pin assignments and bench-confirmed thresholds.

## 11. Highest-value next work

1. Export the 258 configuration records, external DTC identifiers, enable bytes, thresholds, and callback
   pointers into a machine-readable table; this will unlock reliable P/U-code naming across the image.
2. Type the 26-byte lifecycle record and decode its status byte against actual Mode 03/07/0A and service
   `0x19` responses.
3. Trace `FUN_00aacd70()`/`FUN_00aacd58()` through the nonvolatile layer to identify diagnostic blocks,
   integrity checks, save triggers, and power-down ordering.
4. Export the Mode 01 table rows and both callbacks to produce a complete current/freeze-frame PID map.
5. Cross-reference dynamic DTC index tables in `FUN_00a9cde0()`, `FUN_00aa0c48()`,
   `FUN_00aa4698()`, and `FUN_00aa5ccc()` with crank/cylinder and actuator data references.
6. Locate purge/vent outputs and any tank-pressure acquisition from peripheral writes, then work inward to
   the anonymous state machines rather than assuming Evora equivalence.
7. Validate readiness, DTC status, snapshots, and clear behavior with diagnostic captures from an Emira
   ECU before treating inferred bit names as final.

## Conclusion

The Emira application uses a substantially reusable, table-driven diagnostic manager rather than a
collection of independent DTC latches. Its strongest recovered properties are the indexed configuration
model, bounded producer/consumer queue, configurable qualification, shared lifecycle and occurrence
handling, callback-built snapshots, unified clear path, and common OBD/extended-service exposure. O2 and
catalyst monitoring are already visible at useful depth. Fuel monitoring is partially recoverable;
misfire requires cross-subsystem work; EVAP remains unnamed. The decisive next artifact is the 258-entry
DTC dictionary: without it, assigning standard P-codes to internal indices would create false precision.
