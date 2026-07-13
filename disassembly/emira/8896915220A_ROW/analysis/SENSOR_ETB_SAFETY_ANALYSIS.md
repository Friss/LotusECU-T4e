# 8896915220A ROW sensor and electronic-throttle safety analysis

Scope: 2022 Lotus Emira V6 ROW application (`emira.c`). This report traces analog acquisition,
redundant pedal/TPS processing, electronic-throttle adaptation and output, fault propagation, and
the external safety exchange visible in the current MPC5777 export.

Names beginning `DAT_` remain decompiler names. Line references are to the checked-in export;
function addresses are the durable reference if it is regenerated.

## Principal findings

- The application configures two groups of ADC conversion/result machinery and memory transfer
  descriptors, then snapshots dozens of 12-bit-style results from RAM into the live sensor block in
  `adc_sample()`. The RAM layout is confirmed; physical package pins are not.
- Accelerator pedal and throttle position are each dual-channel. Their processors perform range
  checks, scale the channels independently, compare them for agreement, debounce faults, select a
  surviving channel where permitted, and substitute a calibrated safe value for severe faults.
- Local TPS/pedal values are also compared with values returned through a CRC-checked synchronous
  serial exchange. This is an independent safety-companion **communication channel**, not merely a
  second C function using the same local value.
- The ETB has a staged initialization/stop-learning state machine, paired complementary PWM output
  channels, closed-loop control, command/position monitoring, multiple fault grades, and an explicit
  neutral-output helper.
- Sensor faults propagate through shared inhibit words into throttle, load, injection, ignition,
  idle/VVT, rev limiting, diagnostics, and the external companion frame.
- An external supervisor/safety device is evidenced. The current firmware does **not** identify its
  silicon or instruction set, so an HC08 specifically remains unconfirmed. No second HC08 firmware
  image or HC08 memory map is present in this directory.

## Confidence and naming rules

- **Confirmed** means directly observed arithmetic, state transition, hardware-register access, or
  call/dataflow.
- **Inferred** means structure is characteristic and supported by multiple observations, but an OEM
  symbol, unit, or hardware routing remains absent.
- **Unknown** means the export cannot presently distinguish alternatives.

“ADC source” below means a software conversion/result slot. It does not mean a known MPC5777 pin.
Mapping a slot to a connector pin requires SIUL2/pad-mux and board/net evidence.

## Acquisition architecture

### ADC setup and transfer buffers

The post-initialization sequence configures both analog blocks and the transfer layouts before the
control loop starts (lines 23610-23779):

- `FUN_00a09a04()` and `FUN_00a0a680()` initialize two similar conversion engines at hardware bases
  `0xfff80000` and `0xc3e54000` (lines 26808-26849 and 27237-27278).
- `FUN_00a096fc(1/0)` and `FUN_00a0a388(1/0)` perform startup sampling/calibration on five channels
  per instance and calculate gain/offset-like coefficients (lines 26707-26793 and 27123-27222).
- `FUN_00a09c64()` and `FUN_00a0a8a4()` build sequential transfer descriptors whose source fields
  advance from `0x3500000` through `0x3502700`, with additional `0x25029/2a/2b/2c00` entries (lines
  26864-26920 and 27279-27333).
- `FUN_00a09c18()` configures an additional two-entry transfer at RAM `0x40015930` (lines
  26850-26863).

These descriptor patterns and continuously updated result RAM strongly support eDMA-fed ADC result
queues. The export does not name the eDMA channel number or trigger source, so “DMA” is a structural
inference rather than a recovered OEM label.

### Runtime snapshot

`adc_sample()` begins at line 26921. It copies more than fifty results from two RAM
regions:

- `0x400157d6..0x4001581a` into `DAT_40003358..DAT_4000339a`;
- `0x40015998..0x400159c4` into `DAT_4000338c..DAT_400033d0`.

Most values are shifted right four bits, consistent with extracting a 12-bit ADC result from a
16-bit result word. Two sources at `0x4001599c/9e` are shifted by two instead (lines 26956-26963),
which proves different software scaling but not a different physical converter resolution.
`DAT_40003390` receives an additive learned/test offset and is clamped to 0..1023 (lines
26937-26940).

`adc_sample()` is called early in `FUN_00a46058()` alongside sensor state updates and before the ETB
and combustion calculation (lines 58751-58812). Thus downstream control sees a coherent copied
sensor block rather than reading conversion registers ad hoc.

### Smoothing and substitution layers

Raw snapshotting is not the final signal. Confirmed later stages include:

- first-order filtering in `FUN_00a44bec()`: each local TPS-like value and its companion-returned
  counterpart is accumulated as `old*(256-k) + new*k`, then shifted back to an 8-bit result (lines
  58054-58154);
- identical local/companion smoothing for pedal-like channels in `FUN_00a45654()` (lines
  58388-58456);
- three-sample histories for valid scaled channels before redundancy decisions (TPS at lines
  58170-58204; pedal at lines 58488-58518);
- a separately timed sample-and-hold/filter path for the two analog values copied through
  `DAT_40003622/20` and `DAT_4000361e/1c` by `FUN_00a9bdfc()` (lines 93751-93811).

The exact engineering identity of every `DAT_400033xx` member remains unknown. It is unsafe to
publish a complete “ADC channel = sensor” table from adjacency alone.

## Redundant TPS processing

### Local and companion channels

The throttle state machine reads two primary position quantities:

- `__tps_unknown`, used by `get_tps()` and returned as an 8-bit position (lines 43186-43193);
- `_DAT_400157d2`, exposed by `FUN_00a27a18()` in the same scaling (lines 43196-43203).

These two values drive ETB stop learning and closed-loop position selection (lines 42881-43031).
They are software channels; the export does not reveal their package pins.

`FUN_00a44bec()` (`0x00a44bec`, lines 58054-58387) expands the safety comparison. It filters:

- the local `get_tps()` result against companion result `DAT_40015174`;
- the second local TPS result against companion result `DAT_40015175`;
- two further local ADC-derived channels against companion results `DAT_40015173` and
  `DAT_40015179`.

Each local/companion pair has a calibrated difference threshold and persistence timer. Exceeding a
threshold sets bits in `DAT_40006f10` (lines 58079-58166).

### Range, correlation, and channel selection

Two raw TPS inputs arrive as `param_1` and `param_2` to `FUN_00a44bec()`. The function:

1. checks each against calibrated low/high limits at `CALBASE+0x44fc..0x4502`;
2. scales valid channels independently using learned endpoints `DAT_40003726/2e` and slopes
   `DAT_40003734/36`;
3. shifts valid values through three-sample histories;
4. compares the two scaled positions against a calibrated difference threshold
   (`CALBASE+0x442e`), with an additional high-position gate (`CALBASE+0x4430`);
5. records which channel is higher when disagreement persists;
6. selects one channel when only one is usable, or falls back through a timed fault state when
   neither is trustworthy.

The range/scaling path is visible at lines 58168-58218; correlation and selection at lines
58219-58333. Debounced outcomes set `DAT_400034f2` bits 0..5 (lines 58334-58365).

Fault grade `DAT_400034f6 == 1` retains a selected/scaled channel. Grade 2 substitutes a calibrated
safe throttle value from `CALBASE+0x4447 << 4` into `DAT_400034f0` (lines 58366-58387). The same
routine publishes a 0..200 diagnostic percentage.

## Redundant accelerator-pedal processing

`FUN_00a45654()` (`0x00a45654`, lines 58388-58604) is the parallel pedal processor. Its structure is
distinct but analogous:

- local/companion filtered comparisons use `DAT_40015176` and `DAT_40015177` (lines
  58399-58456);
- the two raw inputs are checked against independent ranges at
  `CALBASE+0x4426..0x442c` (lines 58457-58480);
- valid channels are independently transformed by `FUN_00a17644()` and `FUN_00a17678()`, then
  stored in three-sample histories (lines 58481-58517);
- the transformed pair is compared against `CALBASE+0x4404`, while raw-channel agreement is also
  checked (lines 58518-58542);
- the routine chooses a usable channel or zeros both when neither is valid (lines 58543-58567).

The resulting fault grade is `DAT_400034f7`: grade 1 permits degraded operation, while grade 2
forces accelerator demand `DAT_400034e8` to zero (lines 58567-58589). Fault reason bits accumulate
in `DAT_400034ee`, and `DAT_4000382c` receives the selected pedal command.

This is confirmed fail-silent pedal behavior: a severe dual-channel fault does not substitute a
nonzero driver demand.

## ETB initialization, stop learning, and normal control

### Output hardware

`FUN_00a26ff0()` initializes paired eMIOS PWM channels `0x14` and `0x15` at a calibrated frequency
(lines 42831-42848). `FUN_00a25768()` later emits complementary motor commands through those two
logical channels (near lines 42325-42365).

This proves the software eMIOS channel numbers. It does not prove connector pins or H-bridge input
names; those require SIUL2 pad routing and board data.

### State machine

Although currently named `o2_sensor_state_machine___()`, the routine at lines 42864-43181 is the
ETB state machine. Its state values match the declared ETB enum at lines 530-538:

| State | Confirmed behavior |
|---:|---|
| 0 | neutral output; waits for enable |
| 1 | calibrated delay before learning |
| 2 | initializes stop/range learning |
| 3 | monitors position stability through `FUN_00a2683c()` |
| 4 | validates learned endpoints and stores/adopts them |
| 5 | installs calibrated default endpoints |
| 6 | first closed-loop cycle with PID reset |
| 7 | normal closed-loop control and fault monitoring |
| 8 | fault state; neutral output and inhibit set |

`FUN_00a2683c()` slews the motor during learning, stores a 16-sample history, requires 50 stable
comparisons, and captures the learned position pair (lines 42547-42625). A 1000-cycle timeout moves
the system to fallback/fault depending on learned-stage state.

The learned endpoints are checked by `FUN_00a25334()` and recorded by `FUN_00a254a0()`; failures
set diagnostic/safety state and transition to state 8 (lines 42931-43010). Normal states 6/7 call
`FUN_00a25768()` for arbitration and `idle_pid___()` for closed-loop control (lines 43010-43031).

### Command arbitration and motion monitoring

`FUN_00a25768()` (lines 41966-42383) combines driver/idle demand with rev-limit, injector-capacity,
gear, external and fault limits before commanding the motor. It clamps the result, applies separate
opening/closing rates, and drives complementary duty commands.

`FUN_00a28388()` compares the commanded throttle trajectory with measured position. It rate-limits a
model command, calculates tracking error, applies enable gates, and sets DTC `0x97` after calibrated
persistence (lines 43521-43693). This is a confirmed stuck/slow-response monitor, although the OEM
DTC text is not present.

## Safe state, fault latching, and diagnostic propagation

### Neutral motor command

`FUN_00a269f4()` writes 10000 to both PWM channels `0x14` and `0x15` and marks a neutral-command flag
(lines 42627-42636). The ETB state machine calls it in initialization and fault state 8. It is the
software safe/neutral output visible in this export.

The electrical effect—coast, brake, or zero differential H-bridge voltage—is inferred from the
equal complementary commands. Confirming it requires the external driver truth table.

### Latched fault grading

Fault evidence is not a single boolean:

- TPS reason bits: `DAT_400034f2`;
- pedal reason bits: `DAT_400034ee`;
- TPS grade: `DAT_400034f6`;
- pedal grade: `DAT_400034f7`;
- ETB state: `DAT_400064fe`, with state 8 as fault;
- shared inhibit word: `DAT_40003818`;
- external-companion status: `DAT_4001516e/6f`.

`FUN_00a27b70()` converts these states into DTC set/clear operations and shared inhibits (lines
43296-43520). Any missing TPS channel-valid flags set `DAT_40003818 |= 4`; missing ETB validity or
tracking confidence sets `DAT_40003818 |= 0x10000` (lines 43496-43514). The state machine itself
also sets bit `0x10000` when it enters ETB fault state (lines 42896-42913).

Many counters increment toward a fault and decrement toward healing rather than clearing
immediately. DTC status and learned-state persistence should be analyzed separately before calling
every bit ignition-cycle-latched.

## External safety companion and supervision

### Confirmed serial exchange

`FUN_00a9493c()` initializes a dedicated interface and two associated pads/channels (lines
92082-92094). `FUN_00a949b0()` assembles a 20-byte frame containing ETB state, status, engine speed,
fault words, counters, CRC, and fixed trailing identifiers `0xd6..0xdb`, then transmits ten 16-bit
words through `FUN_00a6d858()` (lines 92100-92147).

`FUN_00a6d858()` writes a word through registers based at `0xfff90000`, waits for transfer status,
and captures the received 16-bit word (lines 72448-72474). The register sequence is characteristic
of an on-chip synchronous serial/SPI controller; the exact DSPI instance and chip-select routing are
not symbolized.

`FUN_00a94ae4()` byte-swaps the ten received words, verifies a CRC, checks a rolling sequence byte,
and requires response magic `0x1eec` (lines 92149-92199). Valid responses populate:

- `DAT_4001516e/6f`: remote ETB state/status;
- `DAT_40015174/75`: remote TPS comparison values;
- `DAT_40015176/77` and `DAT_40015173/79`: additional remote sensor comparison values.

Bad magic marks bit `0x40` in remote status. `FUN_00a94be0()` sequences transmit/read phases,
retries failed validation, uses a five-count receive-valid timeout and enters a persistent failure
phase that increments `DAT_4000360c` (lines 92202-92278).

This makes the safety channel independent in the dataflow sense: returned sensor/state values cross
the serial peripheral and are independently framed, sequenced and CRC-checked before local use.

### Is it an HC08?

**No HC08 identity is confirmed.** The evidence proves an external synchronous-serial safety
participant, but none of the following was found:

- an HC08 firmware image or HC08 vector table;
- HC08 opcodes/disassembly;
- a device ID, part number, source symbol, or diagnostic string naming HC08;
- a board-level net tying this chip-select to an HC08.

The device could be a small safety MCU, an ETB-specific supervisor, or another intelligent mixed-
signal device. Calling it “the HC08” would currently import an Evora conclusion without Emira
evidence. A schematic, PCB trace, part marking, or capture of the serial protocol is needed to
identify it.

### On-chip watchdog evidence

At reset, `init()` writes `0xd928` to `0xfff38010` and configures `0xfff38000` before RAM
initialization (lines 21054-21069). Those addresses and key-like value are consistent with the
MPC5777 software-watchdog block. This confirms early watchdog-register handling, but the decompiler
typing is poor and no recurring service routine has yet been positively identified. Exact enable,
window and timeout semantics should be checked against the MPC5777 reference manual before claiming
the watchdog remains active in normal operation.

The serial companion timeout is separate from the on-chip watchdog: it detects missing/invalid
external safety responses and feeds ETB fault logic, while the on-chip watchdog resets or supervises
CPU execution.

## Propagation into load, fuel, ignition, and other control

### Throttle and idle

Severe TPS, pedal, tracking, or companion-state faults force ETB state 8, the equal-duty neutral
command, and shared inhibit `DAT_40003818.16` (lines 42896-42913). `FUN_00a25768()` additionally
limits command using the fault words and substitutes a calibrated small command under severe TPS
conditions (lines 42024-42070).

### Load and air

`engine_load___()` switches selected load source when `DAT_40003428` is false or inhibit bit 4 is
set, choosing a fallback/model path instead of the normal path (lines 37826-37842). TPS/load
plausibility faults therefore affect both actuator authority and cylinder-charge estimation.

### Fuel

`injection()` selects between `load_` and an alternate load variable according to calibration and
fault/source state (lines 49854-49875 and 50180-50217). Its final enable gate can force both bank
pulses to the minimum 160 value rather than calculated pulses (lines 50436-50448). Sensor faults also
alter temperature/start/load correction eligibility through shared inhibit bits.

### Ignition

`ignition___()` tests multiple `DAT_40003818` bits before enabling operating-state corrections and
PWM/status actions (for example lines 32164-32214 and 32492-32540). The later timing aggregator
`FUN_00a92f30()` also imports TPS, pedal, ETB state, gear, fault and remote-companion values before
forming six cylinder outputs (lines 91070-91490).

### VVT, idle target, and rev limit

The VVT controller disables or substitutes cam targets when shared inhibit bits 5/6 are set (lines
38391-38420 and 39720-39760). Idle target construction tests the same global faults and ETB state
throughout `FUN_00a30f20()` (lines 48267-49064). `revlimit()` selects conservative alternate paths
when fault masks are active (lines 41258-41384).

### External cross-monitor

The locally calculated ETB state, engine speed and fault words are transmitted to the companion in
`FUN_00a949b0()`. Returned state and sensor values are then used in local plausibility checks. This
is a closed cross-monitoring loop: local faults influence the outbound frame, and missing or
disagreeing remote results reduce local throttle authority.

## Software channels versus physical assignments

Confirmed software/hardware-controller identifiers:

| Item | Confirmed identifier | Physical assignment status |
|---|---:|---|
| ETB motor PWM A/B | eMIOS `0x14`, `0x15` | connector/H-bridge pins unknown |
| external companion bus | controller at `0xfff90000` | instance/chip select/net unknown |
| local TPS primary | `__tps_unknown` | ADC/result source and pin unknown |
| local TPS secondary | `_DAT_400157d2` | ADC/result source and pin unknown |
| ADC result bank A | RAM `0x400157d6..0x4001581a` | conversion slots visible; pins unknown |
| ADC result bank B | RAM `0x40015998..0x400159c4` | conversion slots visible; pins unknown |
| companion TPS values | `DAT_40015174/75` | remote ADC and pins unknown |
| companion sensor pair | `DAT_40015176/77`, `15173/79` | likely pedal/ETB safety values; exact assignment inferred |

The last pair is called “likely” because its processor mirrors pedal redundancy, but the remote
protocol has no named field definitions.

## Confirmed facts, inferences, and remaining unknowns

### Confirmed

- two large continuously updated analog-result banks and startup calibration;
- coherent runtime snapshot and later first-order smoothing;
- dual TPS and dual pedal range/correlation checks;
- degraded single-channel selection and severe-fault substitutions;
- ETB endpoint learning, timeout/default path, normal closed-loop state, and fault state;
- complementary PWM channel outputs and equal-duty neutral command;
- command-versus-position monitor with DTC persistence;
- CRC/sequence/magic-checked synchronous serial exchange with an external safety participant;
- propagation of sensor/ETB faults into shared powertrain inhibit words.

### Inferred

- descriptor engines are eDMA feeding ADC result queues;
- equal PWM duties electrically produce zero motor torque;
- the serial peripheral is an SPI/DSPI instance;
- remote fields paired with the pedal routine are companion pedal/safety ADC values;
- startup accesses at `0xfff38000` are MPC5777 SWT configuration.

### Unknown

- physical ADC module/channel and package pin for each sensor;
- connector-pin and H-bridge mapping for eMIOS `0x14/0x15`;
- electrical transfer functions and whether redundant channels have opposed slopes;
- whether the external safety device directly disables the H-bridge in hardware;
- the external device's processor family—HC08 is not established;
- normal-operation on-chip watchdog service and timeout configuration;
- which DTC states persist in EEPROM across key cycles versus heal only in RAM.

## Highest-value next steps

1. Decode the `0xfff90000` peripheral instance and chip-select pad in SIUL2 setup, then trace it on
   the ECU PCB to identify the external safety device.
2. Name the twenty-byte companion protocol from transmit and receive offsets; capture it on hardware
   and verify timing, CRC and response to induced TPS/pedal faults.
3. Trace writers of `__tps_unknown`, `_DAT_400157d2`, and the two raw parameters passed to
   `FUN_00a44bec()` / `FUN_00a45654()` back to exact ADC result descriptors.
4. Decode SIUL2 pad mux and ADC channel-selection registers to build a software-channel-to-pin map;
   do not infer pin numbers from RAM ordering.
5. Trace the eMIOS `0x14/0x15` pad routing and external driver truth table to prove whether equal duty
   means coast or active brake and whether the companion owns an independent enable line.
6. Identify recurring accesses to the MPC5777 SWT service register and verify actual watchdog mode
   against the reference manual.
