# Emira Torque, Transmission, Cruise, and Traction Analysis

## Scope and confidence convention

This report follows the ROW application image from accelerator-pedal acquisition through demand shaping, throttle/load limiting, gear recognition, shift-related coordination, and the fast fuel/spark actuators that can reduce engine output. It also records what can—and cannot—yet be proved about cruise, ESP/traction, launch control, and torque transmission over CAN.

- **Confirmed** means the behavior is visible directly in executable data flow.
- **Inferred** means the data flow is real but an anonymous global or interface prevents a firm physical label.
- **Retained vocabulary** means a type or enum survived in the decompilation, but no active ROW execution path has yet been tied to it.
- **Unknown** means the current decompilation does not support the requested identification.

The central conclusion is that this is primarily a **load/throttle-based coordinator with layered limit selection**, supplemented by faster per-cylinder fuel and spark control. It is not yet justified to describe the recovered code as a clean, named “requested torque in Nm” pipeline. Although torque-scaled typedefs exist, the important runtime globals remain anonymous and several command domains are 10-bit/12-bit normalized quantities rather than demonstrably Nm.

## Executive summary

1. **Confirmed driver-demand path:** two accelerator ADC channels are processed together, a selected pedal value is mapped through different 16-point tables according to transmission/configuration, gear/mapping state, and mode bits, then scaled and reconciled with an external request/status bundle (`DAT_40003704/08`). The result is published internally by `FUN_00a4f648` ([emira.c:34108](../emira.c#L34108), [emira.c:34133](../emira.c#L34133), [emira.c:34179](../emira.c#L34179), [emira.c:34202](../emira.c#L34202), [emira.c:34302](../emira.c#L34302), [emira.c:34387](../emira.c#L34387), [emira.c:34433](../emira.c#L34433)).
2. **Confirmed slow-path arbitration:** `FUN_00a25768` forms a candidate from pedal/load demand, substitutes or clamps an external request (`DAT_40003680`) according to status bits (`DAT_40003682`), then applies rev-limit, fault, airflow/injector-capacity, coolant/load, gear, idle, and slew-rate constraints. The operative pattern is repeated minimum selection followed by rate limiting ([emira.c:41966](../emira.c#L41966), [emira.c:41996](../emira.c#L41996), [emira.c:42007](../emira.c#L42007), [emira.c:42040](../emira.c#L42040), [emira.c:42137](../emira.c#L42137), [emira.c:42143](../emira.c#L42143), [emira.c:42147](../emira.c#L42147), [emira.c:42181](../emira.c#L42181), [emira.c:42192](../emira.c#L42192), [emira.c:42335](../emira.c#L42335)).
3. **Confirmed transmission variants:** the application selects separate manual/IPS pedal, ignition, gear-limit, and other calibration families. A six-speed ratio estimator divides engine speed by a drivetrain-speed input and compares it with six calibrated windows; another path can obtain gear from an external interface ([emira.c:34202](../emira.c#L34202), [emira.c:90913](../emira.c#L90913), [emira.c:91567](../emira.c#L91567), [emira.c:91700](../emira.c#L91700), [emira.c:91723](../emira.c#L91723), [emira.c:91727](../emira.c#L91727), [emira.c:91746](../emira.c#L91746), [emira.c:91797](../emira.c#L91797)).
4. **Confirmed shift-coordination machinery:** an optional module calculates per-gear candidates and flags, uses current gear, drivetrain speed, pedal, acceleration-like signals, and six gear-dependent speed values, then publishes two flags plus two gear/state values through `FUN_00a4fe28`. The precise names “rev match,” “upshift request,” and “downshift request” remain inferred rather than proved ([emira.c:46894](../emira.c#L46894), [emira.c:46913](../emira.c#L46913), [emira.c:46937](../emira.c#L46937), [emira.c:47290](../emira.c#L47290), [emira.c:47321](../emira.c#L47321), [emira.c:47345](../emira.c#L47345), [emira.c:47348](../emira.c#L47348)).
5. **Confirmed fast output reduction mechanisms:** a six-cylinder mask generator can select zero through six cylinders in balanced/rotating patterns, and the ignition subsystem maintains six cylinder-specific timing outputs before scheduling each event. A hard rev limit also asserts injection-control flags immediately ([emira.c:29241](../emira.c#L29241), [emira.c:29249](../emira.c#L29249), [emira.c:29268](../emira.c#L29268), [emira.c:29303](../emira.c#L29303), [emira.c:91068](../emira.c#L91068), [emira.c:91307](../emira.c#L91307), [emira.c:91527](../emira.c#L91527), [emira.c:50405](../emira.c#L50405), [emira.c:50412](../emira.c#L50412)).
6. **Cruise, traction, ESP, launch, and transmitted torque are only partly recovered:** explicit enum vocabulary exists, and anonymous external status/limit inputs clearly receive authority in the demand arbiter. However, enum declarations alone do not establish that these features are enabled in this ROW calibration, and the current trace does not prove their signal-to-CAN mapping.

## 1. Driver pedal to normalized engine demand

### 1.1 Redundant pedal acquisition

`FUN_00a16c6c` reads two ADC-derived words at `0x400157cc` and `0x400157ce`, shifts each to a 12-bit-like value, and passes both to the dual-channel pedal processor `FUN_00a45654`. It then consumes the selected/validated pedal value `DAT_400034e8` ([emira.c:34133](../emira.c#L34133), [emira.c:34158](../emira.c#L34158), [emira.c:34160](../emira.c#L34160)). This is confirmed redundant sensing; the detailed plausibility diagnostics are covered separately in `SENSOR_ETB_SAFETY_ANALYSIS.md`.

### 1.2 Map-family selection

The selected pedal value indexes one of several 16-point maps. Selection depends on:

- transmission/configuration result `FUN_00a73414()`;
- mapping state `DAT_400032c0`;
- current estimated/reported gear `DAT_40003608`;
- mode bits including `DAT_40003774 & 0x20`;
- other enable/fault/status inputs.

The normal state selects separate calibration pairs at `CALBASE+0x29de/0x29fe` versus `+0x2c5e/0x2c7e`, with alternate mode tables at `+0x2a1e/0x2a3e` versus `+0x2cde/0x2cfe` ([emira.c:34202](../emira.c#L34202), [emira.c:34204](../emira.c#L34204), [emira.c:34206](../emira.c#L34206), [emira.c:34210](../emira.c#L34210), [emira.c:34214](../emira.c#L34214), [emira.c:34217](../emira.c#L34217), [emira.c:34221](../emira.c#L34221)). Low/high-gear or shift-related states use still more table families ([emira.c:34180](../emira.c#L34180), [emira.c:34238](../emira.c#L34238)). This agrees with the retained accelerator-map states `low_gear`, `shifting`, and `high_gear`, but the enum itself is not referenced symbolically in executable code ([emira.c:351](../emira.c#L351)).

### 1.3 Scaling, external reconciliation, and publication

The mapped value `DAT_40003676` is multiplied by a byte-scale factor `DAT_400036dc/255`, producing `DAT_400060ea` ([emira.c:34295](../emira.c#L34295), [emira.c:34299](../emira.c#L34299), [emira.c:34302](../emira.c#L34302)). The function then compares it with `DAT_40003704` while interpreting request/status bits in `DAT_40003708`; it can choose the external value, modify handshake bits, time out an override, or force the public byte demand to zero ([emira.c:34137](../emira.c#L34137), [emira.c:34139](../emira.c#L34139), [emira.c:34303](../emira.c#L34303), [emira.c:34310](../emira.c#L34310), [emira.c:34335](../emira.c#L34335), [emira.c:34387](../emira.c#L34387), [emira.c:34420](../emira.c#L34420), [emira.c:34430](../emira.c#L34430)). Finally, it publishes selected pedal, severe-fault state, and the reduced byte demand through `FUN_00a4f648` ([emira.c:34433](../emira.c#L34433)).

**Interpretation:** this is confirmed external authority over driver demand, but the producer of `DAT_40003704/08` is not named. It could contain transmission, cruise, or stability coordination. Assigning individual bits to ESP or cruise would currently be speculative.

## 2. Load/throttle arbitration and limit order

`FUN_00a25768` is the best recovered view of the slow engine-output arbiter. The quantities behave like normalized load/throttle commands (several clamp at `0x400` or `0xfff`); they should not be relabeled Nm without a separate conversion trace.

The visible ordering is:

1. **Driver/load baseline.** Form a candidate from `DAT_400034bc + 4*DAT_40003678` ([emira.c:41991](../emira.c#L41991), [emira.c:42003](../emira.c#L42003), [emira.c:42005](../emira.c#L42005)).
2. **Rev-limit substitution.** When rev-limit flags and calibration permit, substitute a separate rev-limit request before the baseline is formed ([emira.c:41996](../emira.c#L41996), [emira.c:41999](../emira.c#L41999)).
3. **External request/limit.** `DAT_40003680`, qualified by the low five bits and bit `0x10` of `DAT_40003682`, may be ignored, used as a ceiling, or used as a substitute; values are capped at `0x400` in one branch ([emira.c:42007](../emira.c#L42007), [emira.c:42009](../emira.c#L42009), [emira.c:42013](../emira.c#L42013), [emira.c:42028](../emira.c#L42028), [emira.c:42033](../emira.c#L42033)).
4. **Throttle/fault state restriction.** Three throttle-state getters and fault globals can replace or cap the candidate ([emira.c:42040](../emira.c#L42040), [emira.c:42042](../emira.c#L42042), [emira.c:42047](../emira.c#L42047), [emira.c:42048](../emira.c#L42048)).
5. **Cylinder/fault fallback restrictions.** Cylinder-count/fault state `DAT_400035f5` chooses calibrated caps, with additional bitfield-driven restrictions ([emira.c:42056](../emira.c#L42056), [emira.c:42065](../emira.c#L42065), [emira.c:42071](../emira.c#L42071)).
6. **Temperature/load and transmission-dependent limits.** Manual/IPS selection changes table families, and coolant/load tables produce another ceiling ([emira.c:42101](../emira.c#L42101), [emira.c:42105](../emira.c#L42105), [emira.c:42113](../emira.c#L42113), [emira.c:42124](../emira.c#L42124), [emira.c:42127](../emira.c#L42127)).
7. **Airflow/injector capacity and idle/load controller.** An engine-speed/timing-based lookup is minimized with `DAT_400032e0`, then `FUN_00a18ca4` computes another bound. The lower result wins ([emira.c:42140](../emira.c#L42140), [emira.c:42143](../emira.c#L42143), [emira.c:42147](../emira.c#L42147), [emira.c:42154](../emira.c#L42154), [emira.c:42167](../emira.c#L42167), [emira.c:42170](../emira.c#L42170)).
8. **Final ceiling selection.** `DAT_4000652a`, a calibrated floor/ceiling path, and `DAT_40001e9a` further constrain the chosen value ([emira.c:42181](../emira.c#L42181), [emira.c:42187](../emira.c#L42187), [emira.c:42192](../emira.c#L42192)).
9. **Gear- and transmission-dependent slew control.** Gear 0–6 selects different manual/IPS calibration bytes. Separate increase and decrease limits move the command toward the chosen target; intervention and rev-limit states can bypass normal slew behavior ([emira.c:42196](../emira.c#L42196), [emira.c:42211](../emira.c#L42211), [emira.c:42215](../emira.c#L42215), [emira.c:42259](../emira.c#L42259), [emira.c:42335](../emira.c#L42335), [emira.c:42346](../emira.c#L42346), [emira.c:42356](../emira.c#L42356), [emira.c:42365](../emira.c#L42365)).

This ordering shows that driver demand does not directly command the ETB. It proposes a target into a lower-wins arbitration chain. External coordination has authority early, safety and capacity caps apply afterward, and the final slow actuator request is normally rate limited.

## 3. Manual and IPS transmission behavior

### 3.1 Confirmed calibration bifurcation

`FUN_00a73414()` repeatedly selects two transmission variants. Examples include separate pedal maps above, separate base-ignition maps (`CALBASE+0x49a0...` versus `+0x1d5e...`) ([emira.c:90913](../emira.c#L90913), [emira.c:90919](../emira.c#L90919), [emira.c:90924](../emira.c#L90924)), separate six-value ratio/gear calibrations ([emira.c:91797](../emira.c#L91797), [emira.c:91807](../emira.c#L91807)), and separate per-gear slew/limit bytes in the throttle arbiter ([emira.c:42215](../emira.c#L42215), [emira.c:42224](../emira.c#L42224), [emira.c:42259](../emira.c#L42259)).

The code proves two distinct driveline configurations. Existing names and function behavior strongly support **manual versus IPS automatic**, but which boolean value represents which variant should be treated cautiously where the decompiler’s `ips_trans` name is not directly propagated.

### 3.2 Six-speed gear estimation

`FUN_00a93df4` obtains a drivetrain-speed-like input and engine speed, calls `FUN_00a940ac`, and stores current gear in `DAT_40003608` ([emira.c:91567](../emira.c#L91567), [emira.c:91577](../emira.c#L91577), [emira.c:91579](../emira.c#L91579)). When local estimation is enabled and the denominator is nonzero, `FUN_00a940ac` calculates `(engine_speed*1000/4)/drivetrain_speed`, then compares the ratio with six calibrated lower/upper windows and returns gear 1 through 6, otherwise neutral/unknown 0 ([emira.c:91719](../emira.c#L91719), [emira.c:91723](../emira.c#L91723), [emira.c:91727](../emira.c#L91727), [emira.c:91746](../emira.c#L91746), [emira.c:91753](../emira.c#L91753), [emira.c:91781](../emira.c#L91781)). In another configuration, gear is taken from an external interface rather than estimated ([emira.c:91708](../emira.c#L91708), [emira.c:91710](../emira.c#L91710), [emira.c:91712](../emira.c#L91712)).

### 3.3 Shift requests, target gears, and possible rev matching

`FUN_00a2e63c` is guarded by a calibration/configuration enable function. Disabled state clears all candidate, timer, flag, and target fields ([emira.c:46894](../emira.c#L46894), [emira.c:46900](../emira.c#L46900), [emira.c:46940](../emira.c#L46940), [emira.c:46942](../emira.c#L46942), [emira.c:46954](../emira.c#L46954)). Enabled operation uses current gear, drivetrain speed, engine speed, pedal demand, acceleration-like inputs, hysteresis timers, and six gear-dependent values returned by `FUN_00a9409c`. It derives candidate gears and directional flags, with special codes `0xe` and `0xf` in some external transmission states. It then emits:

```text
FUN_00a4fe28(flag_bit_2, flag_bit_6, DAT_40006729, DAT_4000670a)
```

([emira.c:47300](../emira.c#L47300), [emira.c:47309](../emira.c#L47309), [emira.c:47315](../emira.c#L47315), [emira.c:47332](../emira.c#L47332), [emira.c:47345](../emira.c#L47345), [emira.c:47348](../emira.c#L47348)).

This is **confirmed shift/gear coordination**. A target engine-speed calculation per possible gear is consistent with rev matching, and the retained IPS enum includes `pre-shift`, `speed-match`, `re-engage`, and `recovery` ([emira.c:181](../emira.c#L181)). However, no symbolic reference ties that enum to `DAT_40006729/0a`, and the final actuator that would close a rev-match loop has not been traced. Therefore:

- up/down candidate and target-gear coordination: **confirmed**;
- automatic transmission state exchange: **strongly inferred**;
- active throttle-blip/rev-match controller in this ROW image: **not yet proved**.

## 4. Fast versus slow engine-output reduction

### 4.1 Slow path: electronic throttle/load

The demand arbiter above normally changes a 12-bit target through gear-dependent rise/fall limits ([emira.c:42335](../emira.c#L42335), [emira.c:42341](../emira.c#L42341), [emira.c:42346](../emira.c#L42346), [emira.c:42356](../emira.c#L42356)). This is appropriate for smooth driver, cruise, transmission, or sustained traction authority. Certain fault/intervention and rev-limit cases bypass the normal limiter ([emira.c:42335](../emira.c#L42335), [emira.c:42338](../emira.c#L42338), [emira.c:42365](../emira.c#L42365)).

### 4.2 Fast path: cylinder fuel masking

The combustion scheduler interprets `DAT_4000368c` as a requested masking/count state. Zero initializes all six mask bytes to `0xff`; six sets all six to zero; intermediate values clear selected cylinder entries in balanced patterns—opposed pairs for two, every other cylinder for three, and rotating selections for other counts ([emira.c:29241](../emira.c#L29241), [emira.c:29250](../emira.c#L29250), [emira.c:29268](../emira.c#L29268), [emira.c:29272](../emira.c#L29272), [emira.c:29276](../emira.c#L29276), [emira.c:29281](../emira.c#L29281), [emira.c:29287](../emira.c#L29287)). When transitioning from an existing cut state it preserves/rotates eligible cylinders using a six-bit mask ([emira.c:29295](../emira.c#L29295), [emira.c:29303](../emira.c#L29303), [emira.c:29306](../emira.c#L29306)).

That is a confirmed rapid cylinder-selection mechanism. Its upstream requester may include rev limiting, traction, transmission shifts, or diagnostics; this trace does not yet assign each source.

### 4.3 Fast path: spark

`FUN_00a92f30` is a six-cylinder ignition aggregation routine: it combines base timing and multiple corrections into per-cylinder arrays, while `FUN_00a93adc(cylinder)` schedules the selected cylinder using its individual timing and duration/dwell value ([emira.c:91068](../emira.c#L91068), [emira.c:91302](../emira.c#L91302), [emira.c:91307](../emira.c#L91307), [emira.c:91311](../emira.c#L91311), [emira.c:91527](../emira.c#L91527), [emira.c:91532](../emira.c#L91532)). This proves fast cylinder-specific spark authority. It does not by itself prove which correction is an ESP or shift retard request.

### 4.4 Hard rev cut

At or above `revlimit_hard_cut_rpm`, injection logic asserts rev-limit and fuel-control flags immediately; hysteresis clears part of the state below the hard limit minus a calibrated margin ([emira.c:50405](../emira.c#L50405), [emira.c:50412](../emira.c#L50412), [emira.c:50416](../emira.c#L50416), [emira.c:50423](../emira.c#L50423)). This is a confirmed example of fast fuel-side intervention operating alongside slower throttle reduction.

## 5. Cruise control authority

The retained throttle-state enum explicitly names `THROTTLE_CRUISE_ACTIVE` and `THROTTLE_CRUISE_INACTIVE`, alongside external torque limit and ESP intervention states ([emira.c:330](../emira.c#L330), [emira.c:335](../emira.c#L335), [emira.c:336](../emira.c#L336), [emira.c:337](../emira.c#L337)). Executable code also proves that an anonymous external request/status pair can override or limit pedal-derived demand and that the later throttle arbiter gives an anonymous external command early authority ([emira.c:34137](../emira.c#L34137), [emira.c:34303](../emira.c#L34303), [emira.c:42007](../emira.c#L42007)).

What is not yet proved is that either anonymous pair is specifically the cruise request, which bits mean set/resume/cancel, or where the vehicle-speed setpoint controller resides. Consequently:

- architecture capable of cruise authority over engine demand: **confirmed**;
- retained cruise state vocabulary: **confirmed**;
- active ROW cruise controller and its state transitions: **unknown from the present trace**;
- cruise CAN IDs and bit layout: **unknown**.

## 6. ESP, traction, wheel speeds, and drive modes

### 6.1 Retained feature model

The recovered type information includes:

- throttle state `THROTTLE_ESP_INTERVENTION` and `THROTTLE_EXT_TRQLIMIT` ([emira.c:330](../emira.c#L330), [emira.c:335](../emira.c#L335));
- traction modes disabled, external-only, enabled, and variable ([emira.c:627](../emira.c#L627));
- vehicle modes Tour, Sport, TC Off, Race, Launch, Launch submode, and Launch fallback ([emira.c:493](../emira.c#L493));
- four signed wheel-speed fields LR/RR/LF/RF ([emira.c:614](../emira.c#L614)).

These are valuable design clues but are **retained vocabulary**, not proof that each mode is active in the 8896915220A ROW calibration. None of these type names is referenced symbolically by the recovered executable.

### 6.2 What the active data flow proves

The active pedal/shift code consumes anonymous status words, a drivetrain speed, a current gear, and acceleration-like signals. The slow arbiter accepts an external ceiling/substitute command; fast cylinder and spark mechanisms exist. Together this is the expected actuator architecture for ESP/traction coordination. The arbiter even applies a distinct scaling when bit 17 of `DAT_40003708` is set ([emira.c:42292](../emira.c#L42292)).

The missing link is provenance: no recovered producer has yet tied that bit or `DAT_40003680/82` directly to an ESP CAN payload, and the four-field wheel-speed struct has not been tied to an executable decoder. Therefore individual-wheel slip calculation, driven-wheel selection, yaw intervention, and the split between ECU-native and external stability control remain **unknown**.

### 6.3 Launch control and mode effects

The launch enum defines inactive, ready, active-hold, and free-rev states ([emira.c:152](../emira.c#L152)). The vehicle-mode enum separately contains three launch-related values ([emira.c:493](../emira.c#L493), [emira.c:498](../emira.c#L498)). Mode bits do select different pedal and throttle-response calibrations—for example `DAT_40003774 & 0x20` changes pedal tables and `&0x100` changes throttle slew maps ([emira.c:34181](../emira.c#L34181), [emira.c:34203](../emira.c#L34203), [emira.c:42307](../emira.c#L42307), [emira.c:42316](../emira.c#L42316)).

Thus **drive-mode-dependent response is confirmed**, but mapping these raw bits to Tour/Sport/Race/Launch is not. An active launch state machine, launch RPM target, clutch/vehicle-speed gates, or launch-specific cylinder cut has not yet been proved.

## 7. CAN and transmitted torque

The only directly identified application receive ID in the relevant inspection is standard CAN ID **0x202**. `flexcan_c_rx_202` verifies the 11-bit ID, copies all eight bytes, and passes the frame onward ([emira.c:25429](../emira.c#L25429), [emira.c:25448](../emira.c#L25448), [emira.c:25458](../emira.c#L25458), [emira.c:25459](../emira.c#L25459), [emira.c:25461](../emira.c#L25461)). Its downstream payload identity was not established, so it must not yet be called a torque, wheel-speed, ESP, cruise, or transmission frame.

The `0x40`–`0x47` FlexCAN handlers elsewhere in the image belong to locked diagnostic/calibration memory access, not drivetrain torque messaging; they are intentionally excluded here.

Internal publisher calls prove that gear/shift state and pedal authority leave their producing modules (`FUN_00a4fe28`, `FUN_00a4f648`, `FUN_00a4fefc`), but they do not prove physical CAN transmission or scaling ([emira.c:34433](../emira.c#L34433), [emira.c:47348](../emira.c#L47348), [emira.c:91635](../emira.c#L91635)). No current evidence supports a specific “actual torque,” “indicated torque,” “driver requested torque,” or “transmission input torque” CAN signal. Those outputs and their IDs remain **unknown**.

## 8. Reconstructed arbitration model

```text
pedal ADC A/B
    -> dual-channel validation/selection
    -> transmission + gear/state + mode-specific pedal map
    -> scale factor
    -> anonymous external request/status reconciliation
    -> normalized driver/engine demand
    -> rev-limit substitution
    -> anonymous external ceiling/substitute
    -> throttle/fault/cylinder fallback caps
    -> temperature/load + manual/IPS limits
    -> airflow/injector-capacity + idle/load controller limits
    -> final ceiling
    -> gear/transmission/mode-specific slew limiting
    -> slow ETB/load command

parallel urgent paths:
    hard rev/fault/anonymous requests
        -> six-cylinder rotating fuel masks
        -> cylinder-specific spark timing/scheduling
```

The important architectural distinction is **authority versus actuator**. Cruise, transmission, or stability software may supply an external limit/request, but common engine arbitration decides the slow throttle/load target. Urgent reduction can additionally use fuel-cylinder selection and spark without waiting for the ETB to move.

## 9. Open questions and next reverse-engineering targets

1. Trace writers of `DAT_40003704`, `DAT_40003708`, `DAT_40003680`, and `DAT_40003682` back to their frame decoders. This is the shortest route to separating cruise, transmission, and ESP authority.
2. Follow `FUN_00a4f648`, `FUN_00a4fe28`, and `FUN_00a4fefc` through the interface layer into CAN packing to identify actual transmitted signals and scale.
3. Trace the six-cylinder count/mask request `DAT_4000368c` backward to enumerate rev-limit, traction, shift, and diagnostic requesters.
4. Find executable users of the four wheel-speed fields rather than relying on the retained `wheelspeeds_int` declaration.
5. Correlate `DAT_40003774` bits `0x20` and `0x100` with decoded mode inputs before assigning Tour/Sport/Race labels.
6. Trace the per-gear speed values returned by `FUN_00a9409c` to the actuator side to decide whether `FUN_00a2e63c` implements rev matching, shift indication, or both.

## Bottom line

The ROW Emira image contains a sophisticated, layered engine-output coordinator: validated pedal demand is shaped by driveline and mode, external modules can request or cap demand, safety/capacity bounds select the lowest allowable slow-path target, and gear-dependent slew limits smooth ETB/load changes. In parallel, cylinder masks and per-cylinder spark provide fast authority. Manual and IPS variants are unequivocally present, including six-speed gear estimation and shift/target-gear coordination.

The code also retains explicit cruise, ESP, traction, race, and launch concepts, but the evidence does not yet justify claiming all are active or assigning their CAN signals. The defensible boundary is: **common external torque/load authority is active; the feature-specific producers and transmitted torque messages remain to be resolved.**
