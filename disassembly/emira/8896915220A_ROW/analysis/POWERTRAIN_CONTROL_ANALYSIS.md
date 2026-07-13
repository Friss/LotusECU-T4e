# 8896915220A ROW powertrain-control analysis

Scope: 2022 Lotus Emira V6 ROW main application (`emira.c`, MPC5777 application entry at
`0x00a00100`). This is a first-pass subsystem map of the current decompiler export. The export is
much less symbolized than `C132E0278.c`; consequently this report distinguishes direct observations
from functional inference and deliberately leaves anonymous state unnamed.

Line references are to the checked-in `emira.c` export. Function addresses are more stable than
line numbers if the export is regenerated.

## Executive summary

The recovered Emira application has the same broad control architecture as the Evora code, but it
is not a simple port:

- `engine_load___()` (`0x00a1d7cc`) constructs cylinder load, several normalized load forms, an
  exhaust-flow estimate and OBD absolute load. Its 20x20 and 16x16 tables and its pressure /
  temperature arithmetic show a modeled-air path, but the exact sensor-source selection is not yet
  fully named.
- `injection()` (`0x00a33b68`) is a two-bank fuel calculation. It uses separate AFR tables, injector
  differential pressure and flow, bank corrections, pulse-width limits, injector-capacity feedback,
  and an explicit six-cylinder trigger pattern.
- Ignition is split between an operating-state routine named `ignition___()` (`0x00a13298`) and a
  later combustion/timing calculation centred on `FUN_00a92f30`. `ign_get_base_timing()` proves
  distinct manual/IPS 20x20 base maps. Six final timing values are passed to the cylinder event
  scheduler by `FUN_00a93adc()`.
- A four-channel closed-loop cam-phasing controller is present at `FUN_00a1ea40()`. It commands eMIOS
  PWM channels `0x46`, `0x42`, `0x44`, and `0x43` and contains four independent position-error and
  learned-offset paths.
- Electronic throttle/idle control is extensive. `FUN_00a25768()` arbitrates multiple limits before
  commanding the paired throttle PWM outputs on channels `0x14` and `0x15`; `idle_pid___()` and
  `FUN_00a30f20()` construct closed-loop idle airflow and target speed.
- `revlimit()` produces separate soft and hard thresholds from coolant/time tables, gear-dependent
  corrections, external requests, and fault fallbacks. `injection()` enforces the hard threshold by
  changing injection-cut flags.
- A ratio-based six-speed gear estimator and manual/IPS variant selection are confirmed. External
  torque/ESP/traction request handling is strongly suggested by the throttle state vocabulary and
  limit arbitration, but the present symbol state is insufficient to label the request messages or
  reproduce the complete torque hierarchy safely.

## Confidence key

- **Confirmed**: the operation, call, table dimensions, or hardware write is directly visible.
- **Inferred**: behavior is strongly supported by structure and surrounding dataflow, but one or
  more inputs or units remain anonymous.
- **Unknown**: the export does not presently support a reliable conclusion.

## Scheduling and dataflow

The phased main loop calls the important paths through timing wrappers:

```text
sensor / state acquisition
       |
       +--> FUN_00a468f0 --> engine_load___()
       |
       +--> FUN_00a467c4 --> injection()
       |
       +--> FUN_00a46eec --> ignition___() and FUN_00a8d544()
       |
       +--> FUN_00a46100 --> gear/state work, revlimit()
       |
       +--> FUN_00a46058 --> throttle state machine and FUN_00a92f30()
```

**Confirmed evidence:** the main phase switch repeatedly dispatches these wrappers at lines
22620-23467. The wrappers call `injection()` at lines 59188-59210, `engine_load___()` at
59280-59303, `ignition___()` at 59576-59604, and `revlimit()` at 58776-58857. The apparently
misnamed `o2_sensor_state_machine___()` is called beside ADC and sensor work at lines 58751-58812,
but its body is actually the electronic-throttle adaptation/control state machine (lines
42864-43181). That symbol should not be used as evidence of oxygen-sensor behavior.

The wrappers are spread across a long phase wheel rather than proving a fixed simple frequency for
each routine. Exact execution rates require the timer/phase increment rate, so this report does not
copy the Evora scheduler frequencies.

## Air charge and load

### Confirmed

`engine_load___()` begins at line 37568 (`0x00a1d7cc`) and:

- derives an RPM-normalized byte and applies an 8x8 correction using an anonymous temperature/load
  pair (lines 37592-37626);
- performs multiple 16x16 lookups indexed by engine speed and an air-path quantity, clamps the
  modeled value to 2000, and combines it with pressure/temperature terms (lines 37645-37703);
- evaluates a 20x20 table indexed by `DAT_40003414` and `DAT_4000378c`, then applies an additional
  correction (lines 37704-37715);
- selects between two calculated load paths using an engine-running/fault gate and forms
  `_load_unknown`-derived load bytes (the selection and normalized outputs are at lines
  37826-37880);
- computes `obd_ii_load_abs` explicitly as cylinder load divided by a calibration at
  `CALBASE+0x16c` (lines 37885-37887);
- computes an exhaust-flow estimate, suppresses it at zero speed, saturates it to 16 bits, and
  publishes it through `get_exhaust_flow__()` (lines 37905-37944).

The selected cylinder-load quantity `_load_unknown` is consumed directly by `injection()` (for
example lines 50038 and 50072), so the load function is on the normal fuel path, not merely OBD
reporting.

### Inferred

The arithmetic around the 16x16 table, pressure-like `DAT_40003788`, temperature-dependent divisor,
and calibration at `CALBASE+0x224` is consistent with a speed-density / volumetric-efficiency
calculation. A second path appears to represent measured or independently modeled air charge and is
selected through `DAT_40003428` / fault bits. This resembles the Evora MAF-versus-fallback design,
but the Emira export does not yet name the MAF channels, MAP value, or source-selection flag.

### Unknown

- Which raw variables are MAF1/MAF2, MAP, barometric pressure, and manifold temperature.
- Whether the fallback path is precisely Alpha-N, speed density, or a blend of both.
- The exact units of `_load_unknown`, although the OBD normalization constant and downstream fuel
  use make cylinder air charge the high-confidence interpretation.
- Supercharger bypass control; no actuator boundary has yet been identified in this pass.

## Fuel and injection

### Confirmed construction

`injection()` begins at line 49740 (`0x00a33b68`) and loops twice, once per bank (lines 49854-50094).
The visible construction is:

1. Manifold-relative fuel pressure is formed by subtracting `DAT_40003772` from
   `DAT_400034aa` and clamping it to 0..65535 (lines 49778-49789).
2. Each bank receives an injector offset/phase value from `FUN_00a338c0()` (lines 49862-49878).
   Injection-angle arithmetic wraps over `0x1c20`, i.e. 7200 tenths of a degree (lines
   49890-49928).
3. Injector flow is read from an 8-point differential-pressure table at `CALBASE+0x5b24`, clamped
   to 5000..20000, and corrected by a 20x20 RPM/load table (lines 49972-49992).
4. Two distinct 20x20 AFR tables are evaluated. The first is the named `CAL_inj_afr1`; the second is
   at `CALBASE+0x77d6`. Both are clamped to byte values `0xaa..0xe6` (lines 49993-50028).
5. `_load_unknown` is divided by the corrected bank fuel quantity to produce the fuel-derived time;
   an existing delivered-fuel/purge-like quantity can reduce it to zero (lines 50065-50085).
6. Coolant/load, load/load and temperature/load tables add multiplicative startup and operating
   corrections (lines 50107-50222). Two bank-specific trim paths then contribute to the final
   pulses (lines 50248-50387).
7. Each final pulse is clamped to a minimum of 160, a software maximum of 270000, and the available
   cycle window `DAT_40003424 * 8 - 250`; duty is reported as 0..100 percent (lines 50330-50409).
8. When enabled, the function publishes an inverse injector-capacity result through
   `DAT_400032e0`, choosing the currently limiting bank (lines 50457-50489). `FUN_00a25768()` later
   includes this value in throttle/load arbitration (lines 42170-42187).

The hard rev limiter crosses into fueling at lines 50410-50435: speed above
`revlimit_hard_cut_rpm` sets cut/status bits in `DAT_400034c0`, with calibrated recovery hysteresis.
If global injection enable is false, both bank outputs are forced to 160; otherwise
`inj_pulsewidth_b1___` and `inj_pulsewidth_b2___` are published (lines 50436-50448).

Six-cylinder phasing is explicit: during initialization/synchronization the code calls
`inj_set_trigger()` for cylinders 0..5 at six evenly spaced angles (lines 50449-50456).

### Inferred / unresolved

The two bank helper structures populated by `FUN_TODO1`, `FUN_00a9cb44`, and `FUN_00a9bb40` likely
contain closed-loop lambda trims, learned trim, purge subtraction, and transient fuel-film state.
The surrounding arithmetic supports those roles, but the helper names and individual terms are not
recovered well enough to assign Evora-style STFT/LTFT labels or exact scaling. The AFR byte encoding
also needs calibration-value validation before quoting commanded AFR in engineering units.

## Ignition, knock, and combustion protection

### Confirmed ignition path

`ign_get_base_timing()` (`0x00a92280`, lines 90913-90931) selects one of two 20x20 RPM/load maps:

- `CALBASE+0x49c8` when `ips_trans` is true;
- `CALBASE+0x1d86` otherwise.

It subtracts 40 from the table byte, matching a quarter-degree-style offset representation. A second
20x20 map at `CALBASE+0x3c66` is returned with the same `-0x28` offset by `FUN_00a9284c()` (lines
90952-90964). `FUN_00a92dc8()` combines this reference with base timing, a low-speed/gear limit, and
two bank-dependent values (lines 90978-91011).

`FUN_00a92f30()` is the central combustion/timing aggregator (lines 91070-91490). It collects RPM,
load, gear, transmission variant, per-cylinder arrays, temperature/air-path state and multiple
limiter inputs, then produces six per-cylinder values in `DAT_40013a44...`. `FUN_00a938bc()` averages
the six values for a common diagnostic/control result (lines 91517-91539). Finally,
`FUN_00a93adc()` scales one cylinder's final angle and calls `FUN_00a0c2c8()` to program the event
(lines 91617-91625).

`ignition___()` (`0x00a13298`, lines 32149-32634) is a separate operating-state/protection layer. It
selects coolant/load-dependent tables, maintains hysteretic state machines, drives two PWM/status
outputs, and gates cold-start/operating states. Its anonymous output variables feed the later
combustion calculation, but their exact OEM names are not recovered.

### Knock: recovered in the follow-up pass

`IGNITION_KNOCK_ANALYSIS.md` supersedes the original first-pass boundary here. The cluster at
`FUN_00a6c8e4()` through `FUN_00a6e6c0()` proves an 11-bin configuration, signed 128-sample ring,
RPM/load cylinder-window scheduling, four DSP modes matching `enum_knock_mode`, two six-cylinder
correction layers consumed by ignition, and six persistent learned values at learned-image offset
`0x394`.

The remaining hard boundaries are narrower: the acquisition ISR body and exact analog/DMA source are
missing, the mode-4 detector callback body is absent, physical sensor/bank mapping is unresolved, and
the stock calibration byte selecting the production DSP mode is unavailable.

## Torque, electronic throttle, and idle

### Confirmed throttle control

`FUN_00a26ff0()` initializes paired PWM channels `0x14` and `0x15` from a calibrated frequency
(lines 42831-42848). `FUN_00a269f4()` commands both channels to 10000, the neutral/safe command in
this representation (lines 42627-42636).

`FUN_00a25768()` (`0x00a254a0`, lines 41966-42383) is the main command arbitration path. It:

- incorporates the rev-limit airflow/load result and external limit/status bits;
- applies gear-indexed limits using `DAT_40003608` values 0..6;
- includes `DAT_400032e0`, the injector-capacity load ceiling from `injection()`;
- constrains and rate-limits the resulting command;
- converts signed controller output into complementary commands for PWM channels `0x14` and
  `0x15` via `FUN_00a6a29c()` (near lines 42325-42365).

`o2_sensor_state_machine___()` is in fact the throttle control/adaptation state machine. It reads two
TPS-like positions, performs a staged stop-learning sequence, enters normal control, and falls back
to `FUN_00a269f4()` under faults (lines 42864-43181). `get_tps()` returns the learned/filtered TPS
quantity as an 8-bit value (lines 43186-43193).

### Idle control

`idle_pid___()` starts at line 42386 and implements resettable proportional/integral/derivative
state with saturation. The throttle state machine calls it with a target, current speed and
setpoint derivative in normal state 7 (lines 43012-43031).

`FUN_00a30f20()` (`0x00a30f20`, lines 48267-49064) constructs the idle-speed target and its
corrections. Confirmed inputs include coolant temperature, gear/transmission variant, load,
vehicle/engine state, startup time, and several accessory/external compensation terms. It computes
multiple speed thresholds and correction tables, maintains learned warm/cold offsets, and publishes
the final target in `DAT_400037a6` (lines 49031-49051).

### Torque and traction: inferred / unknown

The type vocabulary explicitly includes throttle modes `THROTTLE_ESP_INTERVENTION` and
`THROTTLE_EXT_TRQLIMIT` (lines 330-342), plus a traction-mode enum (lines 627-632). The throttle
arbiter visibly combines several external bounds and chooses the most restrictive command. This is
strong evidence that stability/traction and external torque limits enter the throttle hierarchy.

However, enum declarations do not identify the live state variable, and the relevant CAN receive
signals and torque units remain anonymous. Consequently this pass cannot yet confirm:

- which CAN IDs/signals carry ESP, traction, gearbox, or cruise requests;
- the ordering of driver demand, engine maximum torque, gearbox limit, ESP limit and cruise demand;
- whether fast torque reduction uses spark retard, cylinder cut, throttle, or a calibrated blend;
- the exact mapping from modeled torque to throttle/load.

## VVT, thermal management, and idle-related temperature protection

### Confirmed four-channel VVT

`FUN_00a1e944()` initializes four eMIOS PWM outputs at channels `0x46`, `0x42`, `0x44`, and `0x43`
with a common calibrated frequency (lines 38186-38206). `FUN_00a1ea40()` then:

- selects two commanded phase maps from RPM/load, including alternate 6x6 operating regions
  (lines 38232-38379);
- calculates four measured-minus-commanded position errors (lines 38391-38412);
- runs four separate closed-loop paths with integrators, bounded proportional corrections and
  learned offsets (lines 38413-39770);
- writes the four final PWM duties to the same channels (lines 39771-39783).

The four loops and two paired command maps fit dual-bank intake/exhaust cam phasing. Which physical
channel is bank 1/2 intake/exhaust is not yet confirmed. Fault monitoring for the same four loops is
visible in `FUN_00a1fee8()` and associated DTC state (lines 39890-39912 onward).

### Confirmed thermal influence

Coolant temperature directly affects:

- ignition operating-state tables (`ignition___()`, e.g. lines 32215-32358);
- enrichment and startup corrections in `injection()` (lines 50107-50222);
- four alternative rev-limit schedules, each interpolated over coolant temperature and time
  (`revlimit()`, lines 41266-41311);
- the idle-speed target and learned idle offsets (`FUN_00a30f20()`, lines 48336-48431 and
  48873-48965);
- VVT enable/target offsets (`FUN_00a1ea40()`, lines 38232-38420).

### Unknown thermal actuators

This pass did not identify a fan, charge-cooler pump, thermostat, or intercooler-bypass output with
enough confidence to publish an actuator map. Several anonymous single-channel PWM initializers
exist (channels `0x41`, `0x4b`, and `0x56` at lines 47468-47475, 45086-45110, and 54505-54513), but
their surrounding state must be traced to pins and temperature inputs before assigning names.

## Transmission and gear handling

### Confirmed

Manual/IPS selection is repeatedly performed through `FUN_00a73414()`. It chooses distinct ignition
base maps, injection correction tables, rev-limit tables, idle targets, and VVT-related calibrations.
This is stronger evidence than a lone transmission enum: the two variants alter the active
powertrain calibration paths.

`FUN_00a93df4()` begins at line 91655 and updates `DAT_40003608`, the gear index. Its helper
`FUN_00a940ac()` divides engine speed by a drivetrain-speed input, compares the ratio against six
calibrated lower/upper windows, and assigns values 1..6 (lines 91797-91914). The state is then
published to other subsystems and is used by rev-limit and throttle maps.

### Inferred / unknown

The ratio method and explicit six windows identify a conventional six-speed gear estimator. The
exact source of the denominator (vehicle speed, transmission input, or output speed) is not named.
For the IPS variant, shift-state and torque-intervention exchanges are likely present, but the
gearbox message definitions and shift torque phases are not yet mapped.

## Rev-limit and protection boundary

`revlimit()` (`0x00a23e18`, lines 41194-41557) is substantially more than a fixed RPM comparison:

- a 7-point hysteresis offset is indexed by gear (lines 41214-41218);
- coolant/time tables select base limits with separate variant/fault paths (lines 41266-41311);
- additional gear-indexed tables adjust the limits (lines 41319-41336);
- fault state can substitute a conservative fixed limit (lines 41362-41384);
- external requests can lower soft, hard and base limits independently (lines 41413-41444);
- the routine publishes both `revlimit_soft_cut_rpm` and `revlimit_hard_cut_rpm` and computes a
  closed-loop error/control term below the thresholds (lines 41445-41546).

The hardware/combustion action is separated: `revlimit()` calculates thresholds and status, while
`injection()` applies hard-cut flags (lines 50410-50435). Spark/airflow participation below the hard
cut is suggested by the outputs consumed by `FUN_00a25768()` and the ignition aggregator, but its
exact split is not yet named.

## Comparison with the Evora C132E0278 analysis

Confirmed common architecture:

- modeled cylinder load feeds both fuel and load/torque limits;
- two-bank fuel pulse construction uses AFR maps, pressure-dependent flow, trims and a cycle-window
  clamp;
- manual/IPS variants select different ignition, fuel, rev-limit and idle calibrations;
- injector capacity is inverted into a load ceiling;
- rev limiting has distinct soft-control and hard injection-cut boundaries;
- per-cylinder ignition output and four-loop VVT control exist.

Important differences in current evidence:

- Emira's four VVT output channels and closed-loop implementation are particularly clear.
- Emira's throttle stop-learning/fallback state machine is visible, but is presently mislabeled as
  an O2 routine.
- Emira's knock DSP and correction pipeline is now structurally recovered, but Evora remains better
  symbolized at the acquisition ISR, physical bank assignment, and detector callback boundary.
- Emira transmission/traction/torque request handling is less recoverable because CAN signals and
  the limit variables remain largely anonymous.

## Highest-value next steps

1. Rename the throttle routines and TPS variables beginning at `o2_sensor_state_machine___()`, then
   trace every external limit entering `FUN_00a25768()` back to CAN receive code.
2. Recover the missing knock acquisition ISR and mode-4 detector callback, then extract stock mode,
   frequency, window, threshold, and correction bytes from the canonical calibration.
3. Name the air-path sensor variables in `engine_load___()` by following ADC/CAN writers, then
   distinguish measured MAF, speed density and Alpha-N paths.
4. Decode the bank helper structures used by `FUN_TODO1` and `FUN_00a9cb44` to separate STFT, LTFT,
   purge, transient fuel and learned additive time.
5. Trace PWM channels `0x41`, `0x4b`, and `0x56` to the pin configuration and diagnostic monitors to
   identify fans, pumps and other thermal actuators.
6. Resolve the physical assignment of VVT PWM channels `0x42/0x43/0x44/0x46` using capture inputs,
   pin muxing and the four DTC monitor pairs.
