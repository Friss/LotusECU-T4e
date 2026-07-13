# Emira Emissions Diagnostics Analysis

## Scope and evidence rules

This report traces the emissions-control and monitor paths in Emira application `8896915220A_ROW`, with emphasis on oxygen/lambda sensing, heater control, fuel trims, catalyst monitoring, purge/EVAP, readiness, misfire interaction, diagnostic-manager indices, persistence, and control fallback.

Three evidence levels are used:

- **Confirmed** — direct control/data flow in `emira.c`.
- **Inferred** — subsystem role is strongly supported, but a field, physical unit, or failure-mode name is not fully recovered.
- **Unknown** — the present binary does not support a safe assignment.

Internal DTC indices such as `0x2b` or `0xeb` are reported exactly as firmware identifiers. They are **not external SAE P-codes**. No P-code is assigned without recovery of the ECU's internal-to-external DTC dictionary.

The RomRaider XML is definition ID `8900689277A`, while this report analyzes `8896915220A_ROW`. XML labels are therefore used only as corroborating naming hypotheses where address and consumer shape agree; they are not treated as authoritative for this binary.

## Executive summary

The ECU implements a two-bank, four-sensor emissions architecture with:

- two banked primary feedback signals used by a closed-loop lambda controller;
- four independently controlled heater structures and PWM outputs on eMIOS channels `0x51..0x54`;
- banked short-term correction and slower retained correction coupled directly into injection pulse width;
- banked downstream/upstream activity-window comparison for catalyst efficiency;
- a PWM purge/flow actuator on eMIOS channel `0x56`, whose estimated air and hydrocarbon contribution are integrated into bank fueling;
- a persistent readiness byte and persistent catalyst, purge, DTC-history, and snapshot state;
- extensive dependency gating so sensor, fueling, air-path, and misfire faults suspend emissions monitors rather than causing false failures.

The catalyst algorithm sums eight-element bank-specific activity windows and compares `downstream_sum * 1000 / upstream_sum` against calibration. Catalyst readiness bit `0x01` clears only after the two internal catalyst result slots (`0xeb`, `0xec`) reach accepted lifecycle states. Misfire/roughness has its own retained counters and readiness bit `0x04`; its health and intervention state also participate in emissions enable/fallback logic.

Purge control and purge-flow monitoring are confirmed. A separate tank-pressure acquisition, vent-valve owner, sealed-system state machine, or small/large-leak monitor is not yet established, so a complete EVAP leak-detection claim would be premature.

## System flow

```text
primary lambda signals (bank 0/1) ──> closed-loop error/controller ──> bank ST correction
              │                                      │
              │                                      ├──> slow retained correction learning
              │                                      └──> purge-flow eligibility and bank fuel accounting
              │
four sensor/heater states ──> prerequisite health ───┬──> catalyst monitor enable
                                                     ├──> fuel-trim learning enable
upstream/downstream activity windows ────────────────┘
                                                     │
                                                     └──> 8-sample ratio, bank result,
                                                          readiness and DTC manager

crank roughness/misfire ──> per-cylinder/event state ──> emissions gating, retained history,
                                                        readiness and protective response
```

## Oxygen/lambda sensor path

### Banked primary feedback — confirmed

`FUN_00a9b79c()` is the banked lambda-feedback worker. For each bank it obtains:

- current feedback from `FUN_00a111cc(bank)`, backed by `0x40001dee/0x40001df0` (`emira.c:30311-30320`, `93470-93479`);
- a commanded/reference value from the injection path through `FUN_00a33b44()`;
- sensor/control availability and a large bank-specific prerequisite-DTC decision (`FUN_00a9b224()`);
- operating-state gates for temperature, time, engine conditions, and controller state (`emira.c:93116-93290`, `93439-93490`).

It subtracts reference from measured feedback, runs the result through a reusable control structure, scales the controller result into the bank correction at `0x40013b86/0x40013b88`, and publishes activity/error-derived values (`emira.c:93470-93502`). Accessors establish:

| Accessor | Returned state | Use |
|---|---|---|
| `FUN_00a9bb40(bank)` | signed bank feedback correction | Injection pulse correction and OBD trim output (`emira.c:93519-93528`, `50314-50321`, `50365-50372`, `84292-84308`, `84344-84357`). |
| `FUN_00a9bb94(bank)` | closed-loop/feedback availability | Gates slow learning, purge flow/test state, catalyst enable, and other emissions monitors (`emira.c:93561-93565`). |
| `FUN_00a9bbf4(bank)` | bank diagnostic inhibit/invalid state | Catalyst and fuel-monitor prerequisite logic (`emira.c:93611-93620`). |
| `FUN_00a9bba4()` / `FUN_00a9bbc0()` | banked directional/activity flags | Purge feedback classification and other monitor state (`emira.c:93569-93592`, `45228-45240`). |

The exact sensor technology of the primary pair is not named in the code. The data is continuous and participates in signed lambda error control, which is consistent with wideband/linear primary sensors. That technology label remains **inferred**, not connector-level proof.

### Downstream sensing — confirmed as separate pair

Two additional sensor-related values, `DAT_40003618` and `DAT_40003614`, initialize heater structures of type 2 and are processed independently from the primary structures (`emira.c:63386-63388`, `64009-64036`). Their control can be disabled together by a separate availability condition, while the primary pair remains active (`emira.c:63982-64041`).

Catalyst monitoring uses bank-specific upstream/downstream activity arrays in the retained image. That establishes a second sensor role per bank. **Confirmed:** four sensor/heater channels and separate pre/post-catalyst activity are present. **Inferred:** structure order is primary bank 0, primary bank 1, downstream bank 0, downstream bank 1; physical bank numbering and connector pins are not proven.

### OBD exposure

The Mode 01 registry exposes:

- calibration-coded oxygen-sensor presence through PID `0x13` (`emira.c:84516-84528`);
- two-byte bank sensor values from `FUN_00a0a1d4(bank)` and `FUN_00a4d8a4(bank)` (`emira.c:84533-84598`);
- commanded equivalence ratio/lambda through PID `0x44`, encoded in the standardized `2/65536` form from the injection target (`emira.c:84632-84653`).

These service values are externally visible data items, but they do not by themselves establish physical sensor pin order.

## Oxygen-sensor heater control

### Four independent heater outputs — confirmed

`o2_heater_pwm_init_()` initializes eMIOS PWM channels `0x51`, `0x52`, `0x53`, and `0x54` and four `0x3c`-byte control structures at:

- `0x4001b6ec` — type 1;
- `0x4001b728` — type 1;
- `0x4001b764` — type 2;
- `0x4001b7a0` — type 2.

Each receives a 300-count service/test timer (`emira.c:63373-63394`). `FUN_00a4cad8()` executes all four workers with engine speed, electrical supply, exhaust flow, sensor-derived values, availability state, and operating enable, then writes their computed duties to the four PWM channels (`emira.c:63867-64052`).

The output values at structure offsets `+0x38` are fixed-point duties, converted to `0..10000` for the PWM service (`emira.c:63842-63859`, `64049-64052`). When the upstream service is unavailable all four outputs are zeroed; when the downstream pair is unavailable only the last two duties are zeroed (`emira.c:64038-64047`). This is the confirmed safe-output behavior.

### Heater state machine — confirmed, exact states partly unnamed

`FUN_00a4c3bc()` implements states `0..6`:

- reset/disabled initialization;
- enabled warm-up preparation;
- delay and ramp phases;
- closed regulation using filtered electrical/thermal estimates and calibration surfaces;
- externally commanded service/test behavior (`emira.c:63423-63841`).

The worker clamps the final command, filters observed values, applies separate type-1/type-2 calibration blocks, and accepts exhaust flow as a thermal/load input. The exact engineering units of internal fixed-point fields and the service names for states 2–6 remain unknown.

### Heater and sensor monitor indices

Two monitor layers are visible:

1. `FUN_00a176ac()` submits internal indices `0xb9`, `0xba`, `0xbb`, `0xbc`, and `0xbe` from an O2 status bitmap, with calibration enables and debounce counters (`emira.c:34501-34573`). These are confirmed O2 electrical/activity-related slots; their exact failure modes are unresolved.
2. `FUN_00a9cde0(bank)` combines sensor controller status, heater state, signal magnitude/activity, and many calibrated qualifiers. Its initialization assigns five bank pairs:

| Internal indices | Bank split | Confirmed grouping |
|---|---|---|
| `0x2b`, `0x36` | bank 0 / bank 1 | O2/lambda diagnostic class 1 |
| `0x2c`, `0x37` | bank 0 / bank 1 | O2/lambda diagnostic class 2 |
| `0xfa`, `0xfb` | bank 0 / bank 1 | O2/lambda diagnostic class 3 |
| `0xfc`, `0xfd` | bank 0 / bank 1 | O2/lambda diagnostic class 4 |
| `0xfe`, `0xff` | bank 0 / bank 1 | O2/lambda diagnostic class 5 |

The index table is initialized directly at `emira.c:94239-94252`; result submission is at `emira.c:94634-94695`. The individual classes include groups of signal-range, activity, controller-state, and heater-readiness conditions (`emira.c:94314-94617`), but mapping a class to “open circuit,” “slow response,” or an external P-code is not yet justified.

The lambda availability worker itself treats many other bank-specific slots as prerequisites: bank 0 includes `0x2b`, `0x2c`, `0x2e`, `0x2f`, `0xfa`, `0xfe`, `0x4e/0x4f`, `0x52/0x53`, `0x56/0x57`, `0x68`, `0x6a`, and `0x6c`; bank 1 uses the paired indices (`emira.c:93229-93284`). Thus a relevant electrical, activity, heater, or control failure disables closed-loop eligibility before it can corrupt trim learning.

## Short- and long-term trim coupling

### Fast bank correction — confirmed

`FUN_00a9bb40(bank)` is the fast signed feedback correction produced by the lambda controller. The standard Mode 01 trim handlers encode it into one-byte signed-offset form for the first and second banks (`emira.c:84286-84311`, `84335-84360`). Its placement in the PID registry is consistent with bank-1/bank-2 short-term trim slots.

In injection, bank 0 adds:

```text
base_time + (DAT_40003754 * base_time / 1000)
          + (FUN_00a9bb40(0) * bank_fuel_time / 4000)
          + additive terms
```

Bank 1 uses the corresponding `DAT_40003756` and `FUN_00a9bb40(1)` terms (`emira.c:50314-50334`, `50365-50386`). This directly proves that fast feedback and a slower correction both affect delivered fuel rather than existing only for diagnostics.

### Slow/retained correction — confirmed role, partial layout

`FUN_00a290dc()` updates slower bank corrections only when:

- the bank lambda controller is available;
- broad sensor/air/fuel inhibit bitmaps are clear;
- coolant, load, speed, air/load, run-time, and stability windows are satisfied;
- feedback error remains beyond calibrated rich/lean thresholds for calibrated dwell (`emira.c:43999-44335`).

The worker changes retained bytes and words within the learned image beginning at `0x4001735c`, including bank-selected values at offsets `+0x390/+0x392` and larger-table offsets. It then derives `DAT_40003754` and `DAT_40003756` for current operating conditions (`emira.c:44020-44087`, `44138-44258`). Those values are exposed by the next pair of Mode 01 trim handlers (`FUN_00a29c20`, `FUN_00a29c14`) and are consistent with long-term trim bank 1/bank 2 (`emira.c:44368-44380`, `84316-84330`, `84365-84379`).

The learned image is CRC-protected and saved as a whole at orderly shutdown, so these slower corrections survive key cycles; see `LEARNED_DATA_PERSISTENCE_ANALYSIS.md`. The exact division between scalar regions and the two 20-by-20 surfaces at `0x400173a4` and `0x4001755c` remains only partly mapped.

### Calibration-table cross-check

The `8900689277A` RomRaider XML identifies:

- 8-by-8 “lambda trim” tables at calibration `0x0e86` and `0x4ebc`;
- lambda modifier/correction objects at `0x0f5e` and `0x2716`.

The analyzed code performs matching lookup shapes at those addresses (`emira.c:49808-49840`, `50061-50073`). In particular, the `0x0e86/0x4ebc` value is applied only in the active-feedback branch and can reduce the bank target quantity, supporting the XML's broad lambda-trim interpretation. Exact scaling and exact-version equality remain unvalidated.

The same XML calls `0x5b24` “lambda enrichment magnitude,” but the code uses it as an 8-point differential-fuel-pressure-dependent quantity clamped to `5000..20000` before injector-flow/fuel-time construction (`emira.c:49975-49986`). That label should not be used as emissions evidence without further validation.

## Catalyst monitoring

### Enable and prerequisite model — confirmed

`catalyst_monitor_enable_check_()` is bank-aware and dependency-heavy. Before enabling bank 0 it queries internal slots including `0x1c..0x27`, `0xe7`, `0x30`, `0x31`, `0xe8`, `0x33..0x35`, `0x83`, `0x41`, `0x42`, `0x5a`, `0x5b`, `0x5d`, and `0x5f`. Bank 1 uses the shared set plus bank-specific partners `0xe9`, `0x3b`, `0x3c`, `0xea`, `0x3e..0x40`, `0x43`, `0x44`, `0x5c`, `0x5e`, and `0x60` (`emira.c:34808-34903`).

It additionally requires acceptable coolant, load, engine speed, run-state, air/load and temperature windows, bank lambda availability, and no controller/monitor inhibits (`emira.c:34834-34923`). A failed prerequisite clears enable/state bits. It does **not** submit a catalyst failure merely because the test cannot run.

### Efficiency/activity calculation — confirmed

The retained catalyst region contains four eight-byte activity/count vectors and matching calibration identities. For each bank, when its completion marker is armed (`DAT_4001b13d` or `DAT_4001b13e` equals `0xff`) and the bank status bit is set, the monitor:

1. sums eight upstream/activity bytes;
2. sums eight downstream/activity bytes;
3. calculates `downstream_sum * 1000 / upstream_sum`;
4. stores the current ratio at `0x4001b188` or `0x4001b18a`;
5. updates a retained maximum/history value;
6. applies a calibrated threshold and debounce/pass-count policy (`emira.c:34924-34990`).

This is consistent with catalyst oxygen-storage/activity attenuation: a healthy catalyst reduces downstream switching relative to upstream. The exact direction of each array and whether all counted events are literal voltage switches is **inferred**; the ratio and bank separation are confirmed.

The activity vectors, ratios, maxima, completion masks, and calibration identities are in the learned image and are selectively reset if catalyst calibration/variant identity changes (`emira.c:30858-30933`, `31504-31545`). Catalyst monitoring therefore spans multiple trips and is calibration-compatible rather than purely volatile.

### Catalyst result and readiness indices

Internal indices `0xeb` and `0xec` are queried for current, lifecycle, and aging state. When their accepted state combinations indicate completion, catalyst readiness bit `0x01` is cleared in `DAT_4001b13f` (`emira.c:34992-35010`). These are the two bank catalyst result slots at high confidence, but they must not be labeled P0420/P0430 without dictionary proof.

## Purge and EVAP

### Purge actuator, flow, and fuel accounting — confirmed

`FUN_00a3d7b0()` initializes eMIOS PWM channel `0x56` from a calibrated frequency at offset `0x122` (`emira.c:54505-54513`). `FUN_00a3d7f8()` and `FUN_00a3e288()` implement its enable, ramp, test, flow-estimation, and fault state, finally writing channel `0x56` and publishing the command (`emira.c:54515-54985`).

The actuator identification is closed by its fuel/air consumers:

- estimated purge flow is published as `DAT_4000380a` (`emira.c:54947-54950`);
- charge estimation adds the corresponding purge-air contribution to selected cylinder charge (`emira.c:37792-37836`);
- `FUN_00a3d418(bank)` derives a bank-specific hydrocarbon/fuel contribution from purge flow, total charge, and bank feedback (`emira.c:54392-54466`);
- `injection()` subtracts that delivered purge fuel from both bank demands before forming pulse time (`emira.c:50078-50100`).

Thus channel `0x56` is the purge/flow actuator at high confidence. Its exact valve polarity, engineering units, and physical plumbing remain unknown.

The state machine consults internal indices `0x72`, `0x74`, and `0x75` directly and interacts with the broader `0x73..0x80` emissions/misfire prerequisite family. Individual purge electrical, flow, and test-result slots cannot yet be separated safely without the external dictionary and deeper state-to-submission trace.

### Channel `0x4b` is fuel-pressure control, not purge

The superficially similar controller at `FUN_00a2b4e8()`/`FUN_00a2b5a8()` uses a measured pressure difference, a calibrated pressure request, injection-derived delivered-fuel demand, and retained duty learning before writing PWM channel `0x4b` (`emira.c:45086-45397`). It belongs to fuel-pressure regulation and is documented in `FUEL_AIR_ANALYSIS.md`. Its indices `0x17`, `0x18`, `0x95`, and `0xd2` must not be labeled as purge monitors.

### EVAP leak detection — unresolved

This pass confirms canister-purge-like flow control and monitoring. It does **not** conclusively locate:

- a fuel-tank pressure sensor;
- a vent/close valve output;
- a sealed-tank pressure/vacuum test sequence;
- small- versus large-leak result slots;
- EVAP-specific readiness-bit ownership.

Therefore “purge control and purge-flow diagnostics” is supported; “complete EVAP leak detection” is not.

## Readiness, persistence, and clear behavior

`DAT_4001b13f` is the runtime readiness byte returned in the standard four-byte Mode 01 PID `0x01` payload. It initializes from calibration `CALBASE+0x4407` and is retained in the learned image (`emira.c:31174`, diagnostic report `emira.c:77531-77568`). Confirmed bit ownership relevant here is:

| Bit cleared when complete | Family | Evidence |
|---:|---|---|
| `0x01` | catalyst/downstream-sensor efficiency | `catalyst_monitor_enable_check_()`, `emira.c:34992-35010` |
| `0x04` | crank-roughness/misfire family | `FUN_00a6ec14()`, `emira.c:73684-73690`, cross-checked against its per-cylinder crank/misfire state |

Bits `0x20`, `0x40`, and `0x80` are cleared by other monitor clusters, but exact SAE family ownership remains unproved. No EVAP readiness label is assigned.

DTC compact records, auxiliary records, freeze-frame/snapshot banks, readiness, catalyst history, fuel-pressure duty learning, and misfire history all lie within the CRC-protected learned image. Startup validates the whole image and then semantically repairs diagnostic slots through `FUN_00a858cc(1)` (`emira.c:82669-82713`). Mode 04 and extended clear-DTC service `0x14` converge on the shared diagnostic clear machinery; broader learned-data reset also restores emissions histories and readiness defaults.

## Misfire/emissions interaction

`FUN_00a71344()` consumes per-cylinder crank-interval residuals, subtracts retained speed/load baselines, counts cylinder events, and updates retained per-cylinder learning/history (`emira.c:74684-74825`). This is the confirmed crank-roughness/misfire measurement path.

The surrounding emissions monitor `FUN_00a6ec14()` maintains retained event evidence, submits internal index `0x7c`, consults indices `0x73` and `0x7d`, and clears readiness bit `0x04` when the relevant lifecycle conditions are satisfied (`emira.c:73586-73700`). The exact division among random, per-cylinder, and catalyst-damaging misfire results is not yet decoded.

Misfire and emissions control interact in three confirmed ways:

1. lambda availability and catalyst enable consult broad diagnostic and controller inhibit state, including misfire/roughness-related flags;
2. purge flow/test logic checks engine stability and emissions prerequisite slots before using mixture response;
3. retained misfire severity/history is available across trips and participates in a protective output/intervention path through `FUN_00a6bcd8()` (`emira.c:73625-73635`).

The physical protective action behind `FUN_00a6bcd8()` is not resolved here, so it is not labeled “cylinder fuel cut.” Similarly, no P0300/P0301–P0306 mapping is claimed.

## Fallback and fault-containment effects

| Fault/inhibit | Confirmed response | Boundary |
|---|---|---|
| Primary lambda prerequisite failure | Bank closed-loop availability falls false; fast correction/slow learning are no longer trusted; catalyst and purge tests are inhibited. | Exact open-loop commanded mixture remains calibration- and operating-state-dependent. |
| Heater/controller unavailable | Corresponding heater duty is zeroed or state returns to disabled/reset; relevant O2 diagnostic slot is submitted. | Exact electrical failure class per index is unknown. |
| One or more catalyst prerequisites unhealthy | Bank monitor enable and accumulation state are cleared/suspended. | Inability to run is not itself treated as catalyst failure. |
| Purge prerequisites unhealthy | Purge flow/test state is cleared, held, or returned to its disabled path; bank purge-fuel accounting follows the resulting zero/default flow. | Exact valve polarity and individual monitor-slot ownership remain unknown. |
| Calibration/variant identity change | Dependent catalyst vectors/history and learned tables are selectively reset. | Compatible unrelated learned domains remain preserved. |
| Misfire/roughness severity or monitor inhibit | Emissions tests are gated and a protective intervention state can be asserted. | Exact torque/fuel intervention is unresolved. |

## Internal monitor-index ledger

| Indices | Proven association | Confidence |
|---|---|---|
| `0x17`, `0x18` | fuel-pressure control deviation family | Confirmed subsystem cluster; exact names unknown |
| `0x2b/0x36`, `0x2c/0x37` | banked primary lambda diagnostic classes | Confirmed cluster |
| `0xb9..0xbc`, `0xbe` | O2 electrical/activity status bitmap monitors | Confirmed cluster |
| `0x95`, `0xd2` | fuel-pressure actuator/control-state classification | Confirmed subsystem cluster; exact failure names unknown |
| `0xfa/0xfb`, `0xfc/0xfd`, `0xfe/0xff` | banked lambda/heater/activity diagnostic classes | Confirmed cluster |
| `0xeb`, `0xec` | banked catalyst result/lifecycle slots | High confidence |
| `0x73`, `0x7c`, `0x7d` | misfire/roughness monitor family and readiness lifecycle | Confirmed cluster; individual meanings partial |
| `0x72..0x80` | intertwined purge, misfire, and emissions prerequisite/result family | Confirmed dependency/monitor cluster; individual assignments partial |

No row in this table is an external P-code mapping.

## Remaining unknowns

1. Export the 258-entry diagnostic dictionary to map internal indices to external DTC identifiers and descriptions.
2. Prove physical bank/upstream/downstream order of the four sensor and heater structures from pin/peripheral routing.
3. Resolve exact engineering units for primary lambda feedback, heater thermal/electrical state, and the trim controller.
4. Complete the slow-trim field map inside `0x4001735c..0x400189c3`, including the two 20-by-20 retained surfaces.
5. Locate or disprove tank-pressure, vent-valve, and sealed-system leak-test paths for this ROW calibration.
6. Decode the precise misfire severity-to-protective-action chain and readiness/DTC split without assuming P030x/P042x conventions.
7. Byte-compare calibration IDs `8896915220A_ROW` and `8900689277A` before promoting any RomRaider scaling or semantic label to confirmed status.

## Bottom line

The Emira code has a coherent emissions architecture rather than isolated DTC checks. Primary bank lambda control feeds fast correction, slower retained trim, purge fuel accounting, and catalyst eligibility. Four managed heaters support two primary and two downstream sensing roles. Catalyst monitoring compares eight-sample bank activity windows with a scaled ratio and persistent lifecycle state. Purge flow on channel `0x56` is actively modeled into air and bank fuel demand, while full EVAP leak detection remains unresolved. Readiness and DTC persistence are integrated with the learned image, and faults contain their effects by disabling learning or monitor execution before untrusted data can generate secondary emissions failures.
