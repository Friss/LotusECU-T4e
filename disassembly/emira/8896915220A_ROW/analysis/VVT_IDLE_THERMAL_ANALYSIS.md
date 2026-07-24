# Emira VVT, Idle, and Thermal-Control Analysis

## Scope and evidence standard

This report separates three related but independently owned areas in the 8896915220A ROW application:

1. four-channel cam-phase target generation, closed-loop control, output, and diagnostics;
2. idle-speed target construction, feed-forward, feedback, adaptation, gear, and accessory coordination;
3. cooling/accessory PWM control, thermal protection, shutdown residence time, and fault fallback.

“Confirmed” below means executable data flow establishes the behavior. “RomRaider-supported” means an address/function cross-check gives a credible functional name, but not necessarily a physical pin or harness assignment. Anonymous hardware channels are not assigned to bank 1/2, intake/exhaust, fan number, pump, or compressor type unless the evidence supports it.

## Executive summary

- The ECU initializes and commands **four independent VVT PWM outputs** on eMIOS channels `0x46`, `0x44`, `0x42`, and `0x43`. Two RPM/load target-map families are each applied to a pair of measured cam positions. Each loop has error calculation, proportional correction, bounded integral state, fallback/override handling, and individual diagnostics ([emira.c:38186](../emira.c#L38186), [emira.c:38215](../emira.c#L38215), [emira.c:38348](../emira.c#L38348), [emira.c:38413](../emira.c#L38413), [emira.c:38646](../emira.c#L38646), [emira.c:38755](../emira.c#L38755), [emira.c:38779](../emira.c#L38779)).
- RomRaider identifies the `CALBASE+0x0846` target family as intake and `+0x1446` as exhaust, with alternate 6x6 maps at `+0x40aa` and `+0x4122`. This supports calling the two logical families intake-like and exhaust-like, but the physical bank and solenoid assignment of the four PWM channels remains unresolved.
- `FUN_00a30f20` builds idle target/feed-forward from coolant, transmission/configuration, gear/state, vehicle speed, load, accessory demand, startup/operating state, learned temperature-dependent offsets, and multiple correction terms. The final target is saturated into `DAT_400037a6`; a separate commanded load/airflow baseline is published through `DAT_400034bc` ([emira.c:48267](../emira.c#L48267), [emira.c:48336](../emira.c#L48336), [emira.c:48405](../emira.c#L48405), [emira.c:48570](../emira.c#L48570), [emira.c:48580](../emira.c#L48580), [emira.c:49031](../emira.c#L49031), [emira.c:49051](../emira.c#L49051)).
- `idle_pid___` is a conventional saturated P/I/D-plus-feed-forward controller. It drives the paired ETB channels `0x14/0x15` in opposite directions, resets cleanly on state entry, and is forced to the neutral/safe command under faults ([emira.c:42386](../emira.c#L42386), [emira.c:42411](../emira.c#L42411), [emira.c:42422](../emira.c#L42422), [emira.c:42435](../emira.c#L42435), [emira.c:42436](../emira.c#L42436), [emira.c:42503](../emira.c#L42503), [emira.c:42518](../emira.c#L42518)).
- A stateful pressure/load controller on PWM channel `0x41` is strongly consistent with variable-displacement **A/C compressor capacity control**. Its request, active state, and calculated load are explicitly consumed by idle target/feed-forward logic. Physical output identity is RomRaider/context-supported rather than pin-confirmed ([emira.c:47468](../emira.c#L47468), [emira.c:47519](../emira.c#L47519), [emira.c:47601](../emira.c#L47601), [emira.c:47743](../emira.c#L47743), [emira.c:47934](../emira.c#L47934), [emira.c:47951](../emira.c#L47951), [emira.c:48307](../emira.c#L48307), [emira.c:48671](../emira.c#L48671)).
- A second control path drives PWM channel `0x47` from two temperature/status-dependent demand maps, chooses the higher demand, rate-limits it, converts it through `CALBASE+0x5288/0x5290`, and has override/fault fallback. RomRaider calls that calibration “Cooling fan/PWM duty cycle”; the code therefore confirms a cooling PWM strategy and strongly supports a fan controller, though the exact fan/module/pin is not proved ([emira.c:32635](../emira.c#L32635), [emira.c:32654](../emira.c#L32654), [emira.c:32659](../emira.c#L32659), [emira.c:32674](../emira.c#L32674), [emira.c:32685](../emira.c#L32685), [emira.c:32700](../emira.c#L32700), [emira.c:32708](../emira.c#L32708)).
- No charge-cooler pump, electric water pump, or thermostat output is currently proved. Shutdown code confirms coolant/load-dependent after-run **residence time** and registered work requests, but not ownership of channel `0x47` or any pump after key-off ([emira.c:76653](../emira.c#L76653), [emira.c:76657](../emira.c#L76657), [emira.c:76669](../emira.c#L76669), [emira.c:76693](../emira.c#L76693)).

## 1. Four-channel VVT architecture

### 1.1 PWM ownership and frequency

`FUN_00a1e944` initializes four eMIOS PWM channels at a common calibration-controlled period:

| Logical output | eMIOS channel | Runtime duty variable |
|---|---:|---|
| VVT A0 | `0x46` | `DAT_40006394` |
| VVT A1 | `0x44` | `DAT_40006358` |
| VVT B0 | `0x42` | `DAT_40006374` |
| VVT B1 | `0x43` | `DAT_40006392` |

Initialization is at [emira.c:38186](../emira.c#L38186)–[38204](../emira.c#L38204); final writes are at [emira.c:38779](../emira.c#L38779)–[38782](../emira.c#L38782). Getter pairs preserve the same grouping: `FUN_00a206c0` returns A0/A1 duties and `FUN_00a20690` returns B0/B1 duties ([emira.c:39054](../emira.c#L39054), [emira.c:39070](../emira.c#L39070)).

The group labels are deliberately neutral. The RomRaider target association below suggests group A is intake-like and group B exhaust-like, but it does not establish which output is left/right or bank 1/2.

### 1.2 Target family A: RomRaider intake association

The first target is selected from either:

- normal 16x16 map `CALBASE+0x0846`, axes `+0x0826/+0x0836`; or
- alternate 6x6 map `CALBASE+0x40aa`, axes `+0x409e/+0x40a4`.

The controller enters the smaller map only inside a bounded RPM/load-like region with a mode bit and enable latch; transitions reset associated settling state ([emira.c:38232](../emira.c#L38232), [emira.c:38242](../emira.c#L38242), [emira.c:38248](../emira.c#L38248), [emira.c:38251](../emira.c#L38251), [emira.c:38258](../emira.c#L38258), [emira.c:38267](../emira.c#L38267)). RomRaider names these offsets `vvt: intake target` and `vvt: intake target sport`.

Manual/IPS selection adds a calibrated load-based compensation from `+0x16a2/+0x16aa` or `+0x23be/+0x23c6` ([emira.c:38348](../emira.c#L38348)–[38355](../emira.c#L38355)). A 16-point coolant trim at `+0x0946/+0x0956` is subtracted with a zero floor, matching RomRaider’s `vvt: intake coolant trim` description ([emira.c:38380](../emira.c#L38380)–[38382](../emira.c#L38382)).

The resulting target feeds two parallel measured-position paths, producing errors `DAT_400034a2` and `DAT_400034a0` when their corresponding validity flags are set ([emira.c:38356](../emira.c#L38356)–[38367](../emira.c#L38367)). These become the control loops eventually written to channels `0x46` and `0x44`.

### 1.3 Target family B: RomRaider exhaust association

The second target similarly selects between:

- normal 16x16 map `CALBASE+0x1446`, axes `+0x1426/+0x1436`; or
- alternate 6x6 map `CALBASE+0x4122`, axes `+0x4116/+0x411c`.

Selection and hysteresis are visible at [emira.c:38296](../emira.c#L38296)–[38345](../emira.c#L38345). RomRaider names these offsets `vvt: exhaust target` and `vvt: exhaust target sport`.

Two validity-gated errors, `DAT_40003494` and `DAT_40003496`, are constructed from the other measured cam pair and controller offsets ([emira.c:38368](../emira.c#L38368)–[38378](../emira.c#L38378)). These feed the loops written to channels `0x42` and `0x43`.

A separate 16-point coolant trim at `+0x4036/+0x4046` is evaluated in the continuation of the same controller and subtracted with a zero floor before the group-B duties are finalized ([emira.c:38548](../emira.c#L38548)–[38557](../emira.c#L38557)). This directly supports RomRaider's `vvt: exhaust coolant trim` label.

### 1.4 Four independent feedback loops

Each of the four channels has analogous state:

- measured-minus-target error;
- proportional term using `CALBASE+0x1be`;
- integral accumulator using `CALBASE+0x1c0`;
- bounded correction, typically `-400..400` before recentering;
- position/settling learning or averaging state;
- override storage for service/testing;
- a final duty scaled by ten.

For A0, the P term and bounded PI result appear at [emira.c:38413](../emira.c#L38413)–[38436](../emira.c#L38436), and the integrator is bounded at approximately ±`0x19000` ([emira.c:38820](../emira.c#L38820)–[38828](../emira.c#L38828)). A1 follows the same construction beginning at [emira.c:38473](../emira.c#L38473). The B1 loop shows the same error, P, bounded PI, and centered-duty construction at [emira.c:38646](../emira.c#L38646)–[38676](../emira.c#L38676); B0 has the parallel state immediately before it.

Final override selection is explicit: four override flags select either externally supplied duties or internally calculated centered duties, then all four hardware channels are written ([emira.c:38749](../emira.c#L38749)–[38782](../emira.c#L38782)). This confirms four independent closed loops, not two solenoids with duplicated telemetry.

### 1.5 Inhibit and fault fallback

Shared fault bits 5 and 6 in `DAT_40003818` inhibit the two logical VVT groups. If prerequisites or cam-position health are missing, controller state is reset and the output returns to a centered/minimal fallback (`... = 1`, later scaled by ten) rather than continuing to integrate stale error ([emira.c:38380](../emira.c#L38380), [emira.c:38713](../emira.c#L38713), [emira.c:38725](../emira.c#L38725), [emira.c:38749](../emira.c#L38749)).

`FUN_00a1fee8`/`FUN_00a20b88` maintain individual counters and DTC results for all four paths. DTC indices 4–7 cover four control/availability checks ([emira.c:39747](../emira.c#L39747)–[39808](../emira.c#L39808)); DTC indices 8–11 cover directional or tracking failures, including the fourth path at [emira.c:39651](../emira.c#L39651)–[39740](../emira.c#L39740). Aggregate failure then sets group inhibit bits 5 or 6 ([emira.c:39870](../emira.c#L39870)–[39883](../emira.c#L39883)). Initial pass/fail counters are loaded separately for each channel ([emira.c:39890](../emira.c#L39890)–[39905](../emira.c#L39905)).

### 1.6 Physical mapping boundary

The most defensible mapping is:

| Logical family | RomRaider association | Two output channels | Physical bank assignment |
|---|---|---|---|
| A | intake target family | `0x46`, `0x44` | unknown |
| B | exhaust target family | `0x42`, `0x43` | unknown |

Confirming bank and connector names requires correlating these outputs with the four eTPU cam captures documented in `CRANK_CAM_EVENT_ANALYSIS.md`, then checking pad mux/board wiring. Nothing in the current C export proves that ordering.

## 2. Idle target and feed-forward construction

### 2.1 Strategy selection and base target

`FUN_00a30f20` selects four coolant-dependent target families. Transmission/configuration `FUN_00a73414`, a gearbox/state byte, and mode bit `DAT_40003774.4` assign internal strategy codes 1–4 ([emira.c:48325](../emira.c#L48325)–[48377](../emira.c#L48377)). These align with the retained idle-strategy vocabulary (IPS fallback/normal and manual Tour/Sport), but the exact boolean-to-label mapping remains configuration-dependent.

The four RomRaider-cross-checked coolant maps are:

| Strategy family | Axis | Data | Executable use |
|---|---:|---:|---:|
| coolant A | `0x0b6e` | `0x0b7e` | lines 48360–48366 |
| coolant B | `0x13e6` | `0x13f6` | lines 48370–48376 |
| coolant C | `0x277e` | `0x278e` | lines 48342–48355 |
| coolant D | `0x3476` | `0x3486` | lines 48339–48341 |

The target is scaled by four after an encoded offset. A second gear/load/accessory surface may raise it, with separate manual/IPS tables at `+0x5a54/+0x5a5c/+0x5a64` and `+0x5338/+0x5340/+0x5348` ([emira.c:48379](../emira.c#L48379)–[48403](../emira.c#L48403)).

### 2.2 Feed-forward terms

The final idle request is an explicit sum/product of many independently saturated terms ([emira.c:49031](../emira.c#L49031)–[49049](../emira.c#L49049)). Confirmed contributors include:

- load correction at `+0x0702/+0x070a` ([emira.c:48297](../emira.c#L48297));
- transmission-specific engine-state correction at `+0x18d6/+0x18de` or `+0x3a66/+0x3a6e` ([emira.c:48325](../emira.c#L48325)–[48334](../emira.c#L48334));
- a strategy-dependent base feed-forward curve selected after target construction ([emira.c:48459](../emira.c#L48459)–[48497](../emira.c#L48497));
- engine-speed/load surface `CALBASE+0x0376/+0x0386/+0x0396`, named `idle: target RPM base` in RomRaider ([emira.c:48562](../emira.c#L48562)–[48571](../emira.c#L48571));
- signed RPM target correction at `+0x0dc6/+0x0dd6` ([emira.c:48572](../emira.c#L48572)–[48579](../emira.c#L48579));
- transmission-specific vehicle/drivetrain-speed compensation at `+0x265e/+0x266e` or RomRaider-named `+0x22ee/+0x22fe` ([emira.c:48580](../emira.c#L48580)–[48591](../emira.c#L48591));
- acceleration/change correction at `+0x234e/+0x235e` ([emira.c:48593](../emira.c#L48593)–[48599](../emira.c#L48599));
- coolant and speed-dependent startup/base throttle terms ([emira.c:48601](../emira.c#L48601)–[48625](../emira.c#L48625));
- accessory loads at `DAT_400037a0`, `DAT_4000364f`, and `DAT_4000364e` through separate 8-point curves ([emira.c:48670](../emira.c#L48670)–[48697](../emira.c#L48697));
- cylinder/fault compensation from `DAT_400035f5` ([emira.c:48711](../emira.c#L48711)–[48717](../emira.c#L48717));
- low-vehicle-speed/gear compensation dependent on coolant and engine state ([emira.c:48718](../emira.c#L48718)–[48745](../emira.c#L48745)).

The result `DAT_400037a6` is therefore better described as a composite idle load/airflow correction than a pure RPM target. `DAT_400037b4` is the clearer speed setpoint: engine-speed error is explicitly `engine_speed - DAT_400037b4` ([emira.c:48448](../emira.c#L48448)–[48458](../emira.c#L48458)).

### 2.3 Gear and transmission coordination

Manual/IPS selection changes base targets, compensation surfaces, vehicle-speed correction, decay rates, and idle throttle limits. Gear/transmission state `DAT_400034a5`, drivetrain speed `DAT_40003666`, mode bits, and clutch/brake-like flags determine whether corrections engage or decay ([emira.c:48336](../emira.c#L48336), [emira.c:48517](../emira.c#L48517), [emira.c:48580](../emira.c#L48580), [emira.c:48718](../emira.c#L48718), [emira.c:48723](../emira.c#L48723), [emira.c:48731](../emira.c#L48731)). The throttle arbiter separately selects manual/IPS idle angle ceilings at RomRaider offsets `0x0dee`, `0x0dfe`, `0x295e`, and `0x296e` ([emira.c:42101](../emira.c#L42101)–[42122](../emira.c#L42122)).

This proves coordinated driveline idle management. Exact clutch, brake, Park/Neutral, or Drive bit names still require CAN/input provenance.

## 3. Idle feedback controller and ETB actuation

`idle_pid___(target,current,setpoint_deriv,reset)` computes:

- bounded error `target-current` ([emira.c:42422](../emira.c#L42422)–[42428](../emira.c#L42428));
- derivative from a delayed three-sample setpoint/current history and a calibrated D gain ([emira.c:42411](../emira.c#L42411)–[42420](../emira.c#L42420), [emira.c:42430](../emira.c#L42430)–[42434](../emira.c#L42434));
- proportional term using `CALBASE+0xf6` ([emira.c:42435](../emira.c#L42435));
- integral accumulation using `CALBASE+0xfa`, bounded by `CALBASE+0x106` ([emira.c:42404](../emira.c#L42404)–[42408](../emira.c#L42408), [emira.c:42436](../emira.c#L42436)–[42446](../emira.c#L42446));
- state-dependent feed-forward from `+0x195e/+0x197e` or `+0x2c9e/+0x2cbe` ([emira.c:42448](../emira.c#L42448)–[42457](../emira.c#L42457)).

The signed result is normalized against a speed-dependent authority and converted into complementary ETB commands: negative correction holds channel `0x14` neutral while modulating `0x15`; positive correction does the reverse ([emira.c:42471](../emira.c#L42471)–[42483](../emira.c#L42483), [emira.c:42518](../emira.c#L42518)–[42525](../emira.c#L42525)). Engine-off, learned-stop faults, or global throttle faults force both channels to the neutral `10000` command ([emira.c:42503](../emira.c#L42503)–[42516](../emira.c#L42516), [emira.c:42528](../emira.c#L42528)–[42531](../emira.c#L42531)).

The ETB state machine resets PID history when entering control state 6, then runs it continuously in normal state 7. Any severe TPS/ETB condition transitions to state 8 and invokes the neutral fallback ([emira.c:42900](../emira.c#L42900)–[42910](../emira.c#L42910), [emira.c:43000](../emira.c#L43000)–[43031](../emira.c#L43031)).

## 4. Learned idle offsets

Four retained values—`0x40017e10`, `0x40017a00`, `0x40017e12`, and `0x40017a02`—form two temperature-dependent pairs. Depending on accessory/strategy state `DAT_4000379e`, `FUN_00a30f20` selects one pair and linearly interpolates between cold and hot values ([emira.c:48405](../emira.c#L48405)–[48431](../emira.c#L48431)).

During stable idle, accumulated speed error `DAT_400037ac` is compared with calibrated positive/negative learning thresholds. The selected retained value is incremented or decremented one count inside coolant windows, with learned bounds mirrored in working RAM `0x40018a88..96` ([emira.c:48830](../emira.c#L48830)–[48871](../emira.c#L48871), [emira.c:48873](../emira.c#L48873)–[48918](../emira.c#L48918), [emira.c:48920](../emira.c#L48920)–[48970](../emira.c#L48970)).

Confirmed interpretation: persistent, temperature- and accessory-state-dependent idle correction. Likely physical interpretation: learned idle airflow/load offset. Exact units and which pair represents A/C active versus inactive are not renamed here because `DAT_4000379e` is context-derived rather than symbolically named.

## 5. A/C/accessory coordination

### 5.1 Request gating and protection

`FUN_00a2f710` owns a multi-state accessory controller with PWM initialized on channel `0x41` ([emira.c:47468](../emira.c#L47468)–[47473](../emira.c#L47473)). Request permission is inhibited by high coolant, high load, high pedal/load, low RPM, acceleration/pressure limits, fault state, and timers ([emira.c:47504](../emira.c#L47504)–[47574](../emira.c#L47574), [emira.c:47582](../emira.c#L47582)–[47620](../emira.c#L47620)). An external request is converted to `DAT_c3f9069f`; actual active state is mirrored in `DAT_4000379c` ([emira.c:47624](../emira.c#L47624)–[47655](../emira.c#L47655)).

The active control loop tracks `DAT_40006746` against a target/reference, uses proportional, derivative, and slowly adapted terms, constrains output to a calibrated maximum, and writes the resulting duty to channel `0x41` ([emira.c:47722](../emira.c#L47722)–[47741](../emira.c#L47741), [emira.c:47743](../emira.c#L47743)–[47934](../emira.c#L47934), [emira.c:47951](../emira.c#L47951)). Its pressure-like thresholds and direct idle-load handoff make variable-displacement A/C compressor control the strongest interpretation, but the physical pin/valve is not independently verified.

### 5.2 Idle pre-load and active-load handoff

The A/C-like state machine calculates a load quantity `DAT_400037a0` ([emira.c:47934](../emira.c#L47934)), while `DAT_4000379e` marks active engagement states ([emira.c:47743](../emira.c#L47743)–[47753](../emira.c#L47753)). Idle target construction consumes both:

- active request selects a manual/IPS load surface and pre-load allowance ([emira.c:48307](../emira.c#L48307)–[48323](../emira.c#L48323));
- `DAT_400037a0` passes through `+0x0c46/+0x0c56` as an additive feed-forward term ([emira.c:48671](../emira.c#L48671)–[48678](../emira.c#L48678));
- accessory state selects which retained learned idle-offset pair is used ([emira.c:48405](../emira.c#L48405)–[48431](../emira.c#L48431));
- accessory active/inactive state changes correction decay rates ([emira.c:49172](../emira.c#L49172)–[49206](../emira.c#L49206)).

This is confirmed anticipation plus feedback: the ECU raises available idle authority during engagement, then learns separate steady-state corrections.

## 6. Cooling PWM, thermal protection, and fallback

### 6.1 Cooling demand on channel 0x47

`FUN_00a14404` initializes eMIOS channel `0x47` at 100 Hz ([emira.c:32635](../emira.c#L32635)–[32647](../emira.c#L32647)). In the normal run state it calculates two temperature/status-dependent demands from separate 8x8 map families and takes the larger ([emira.c:32654](../emira.c#L32654)–[32679](../emira.c#L32679)). Transmission/configuration changes one map’s input and table family.

The selected demand can be overridden by `DAT_40001e3e`, forced to maximum by a fault bit, forced off when enable status is absent, or replaced with a calibrated fallback ([emira.c:32650](../emira.c#L32650)–[32652](../emira.c#L32652), [emira.c:32681](../emira.c#L32681)–[32690](../emira.c#L32690)). Rising demand is rate-limited by `CALBASE+0x219` ([emira.c:32692](../emira.c#L32692)–[32699](../emira.c#L32699)). It is then converted by the RomRaider-named cooling duty curve at axis `+0x5288`, data `+0x5290` ([emira.c:32700](../emira.c#L32700)–[32701](../emira.c#L32701)).

Output logic deliberately toggles through zero/full states before applying the inverted duty, likely to manage an external PWM module’s startup/wake behavior ([emira.c:32708](../emira.c#L32708)–[32735](../emira.c#L32735)).

Evidence classification:

- temperature-driven cooling PWM controller: **confirmed**;
- cooling-fan/module interpretation: **RomRaider-supported and strong**;
- exact radiator fan, fan count, physical pin, and external module protocol: **unknown**.

### 6.2 Thermal protection outside the fan loop

Thermal management is distributed, not owned solely by channel `0x47`. Coolant temperature also:

- subtracts cold trim and gates VVT enable ([emira.c:38232](../emira.c#L38232), [emira.c:38380](../emira.c#L38380));
- selects idle targets and learned-offset interpolation ([emira.c:48336](../emira.c#L48336), [emira.c:48405](../emira.c#L48405));
- limits maximum throttle/load through `CALBASE+0x1d2e/+0x1d38` ([emira.c:42124](../emira.c#L42124)–[42129](../emira.c#L42129));
- inhibits the A/C-like accessory request above a calibrated threshold ([emira.c:47519](../emira.c#L47519)–[47523](../emira.c#L47523));
- participates in rev-limit, fueling, and ignition protection as documented in `POWERTRAIN_CONTROL_ANALYSIS.md`.

This layered response means loss of cooling authority can be handled both by commanding maximum/fallback cooling duty and by reducing thermal load elsewhere.

### 6.3 Other anonymous PWM channels

Channel `0x4b` at [emira.c:45086](../emira.c#L45086) and channel `0x56` at [emira.c:54505](../emira.c#L54505) remain anonymous. Their surrounding algorithms are not sufficiently tied to coolant/after-run or physical plumbing to call them a charge-cooler pump, water pump, thermostat, or bypass. They are excluded from the thermal actuator inventory until pad and signal provenance is established.

## 7. Shutdown and after-run ownership

The ignition/power state machine enters state 4 after engine stop. It computes `DAT_40009204` from an 8x8 `load_`/`coolant_temp_` table at `CALBASE+0x1836/+0x183e/+0x1846`, scaled by 5000, plus a second calibrated timer ([emira.c:76653](../emira.c#L76653)–[76666](../emira.c#L76666)). State 4 holds ECU power while timers or registered request bits remain active, then proceeds to learned-data writing and final release ([emira.c:76669](../emira.c#L76669)–[76705](../emira.c#L76705)).

This proves **load/coolant-dependent post-key-off residence**. It does not prove:

- channel `0x47` continues to run after key-off;
- any pump is powered during state 4;
- which `DAT_4000920a` request bits belong to cooling, emissions, VVT parking, ETB parking, or persistence.

The ownership boundary therefore matches `START_LOCKOUT_AND_ENGINE_SHUTDOWN_ANALYSIS.md`: after-run scheduling is application-controlled, but thermal actuator participation remains unresolved.

## 8. RomRaider calibration cross-check

The following definitions align directly with executable lookups:

| Domain | RomRaider name | Axis/data offsets | Code |
|---|---|---|---|
| VVT | intake target | `0x0826/0x0836/0x0846` | 38258–38260, 38405–38408 |
| VVT | intake target sport | `0x409e/0x40a4/0x40aa` | 38248–38250 |
| VVT | exhaust target | `0x1426/0x1436/0x1446` | 38312–38314 |
| VVT | exhaust target sport | `0x4116/0x411c/0x4122` | 38302–38304 |
| VVT | intake coolant trim | `0x0946/0x0956` | 38381–38382 |
| VVT | load compensation manual/IPS | `0x16a2/0x16aa`, `0x23be/0x23c6` | 38348–38354 |
| Idle | target correction | `0x0362/0x036a` | 48294–48296 |
| Idle | coolant target families A–D | `0x0b6e..0x3486` | 48336–48377 |
| Idle | target/base surface | `0x0376/0x0386/0x0396` | 48570–48571 |
| Idle | RPM target curve | `0x0dc6/0x0dd6` | 48572–48579 |
| Idle | vehicle-speed compensation | `0x22ee/0x22fe` | 48580–48591 |
| Idle/ETB | idle throttle limits | `0x0de6..0x296e` | 42101–42122 |
| Cooling | PWM duty curve | `0x5288/0x5290` | 32700–32701 |

The address agreement is strong. Descriptive labels such as intake, exhaust, and cooling fan should still be understood as definition-assisted logical naming, not connector-level proof.

## 9. Open questions

1. Correlate VVT PWM channels `0x46/0x44/0x42/0x43` with the four eTPU cam captures and pad mux to assign physical bank/cam names.
2. Identify the source and physical scale of `DAT_40006746` in the channel-`0x41` controller to turn the A/C interpretation into direct sensor proof.
3. Trace `DAT_4000920a` request-bit writers and channel-`0x47` scheduling during power state 4 to determine whether the fan participates in after-run.
4. Resolve PWM channels `0x4b` and `0x56` through pad mux, feedback diagnostics, and wiring before assigning pump/thermostat names.
5. Name the clutch/brake/gear status bits that gate idle learning and low-speed compensation.

## Bottom line

The Emira application has four genuine, independently diagnosed cam-phasing loops organized as two logical target families across two banks. RomRaider offsets strongly identify those families as intake and exhaust, while the exact bank/output order remains unknown. Idle control is equally mature: transmission- and mode-specific coolant targets, feed-forward for load/vehicle/accessory state, separate learned offsets, and a saturated ETB PID all cooperate rather than relying on a single idle table.

Thermally, channel `0x47` is a confirmed temperature-derived cooling PWM with strong fan-definition support, and channel `0x41` is a pressure/state-controlled accessory load with strong A/C compressor-control evidence and explicit idle coordination. The ECU also imposes thermal limits through VVT, throttle, A/C inhibition, fueling, ignition, and rev limiting. What remains unproved is pump ownership and whether any cooling actuator is serviced during the confirmed load/coolant-dependent after-run residence period.
