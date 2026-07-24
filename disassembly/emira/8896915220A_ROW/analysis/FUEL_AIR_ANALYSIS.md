# Emira G6 Fuel and Air Analysis

## Scope, evidence rules, and bottom line

This report traces the application fuel/air path in `8896915220A_ROW`, from charge estimation through
bank fuel calculation and injector output. Function addresses are the durable anchors; line numbers
refer to the checked-in `emira.c` export.

Evidence labels used below:

- **Confirmed** — arithmetic, state selection, lookup dimensions, hardware output, or direct
  producer/consumer flow is visible.
- **Inferred** — the structure strongly identifies the function, but an OEM name, physical source,
  or unit is missing.
- **Unknown** — the available export cannot distinguish plausible alternatives.

The major findings are:

- The ECU has two independent cylinder-charge estimates and explicitly selects a normal versus
  fallback path. One path is pressure/temperature/VE-like and the other is a learned air-path
  model. No defensible MAF sensor identity or MAF transfer function has been recovered, so calling
  this “MAF primary, MAP fallback” would be unsupported.
- `injection()` (`0x00A33B68`) is a genuinely banked calculation: separate commanded-mixture maps,
  closed-loop corrections, learned/additive terms, pulse widths, and duty limits are maintained for
  banks 0 and 1.
- Injector differential pressure is computed and used to index an eight-point characterization.
  The resulting value is bounded to `5000..20000`, then corrected by a 20x20 operating map before
  converting cylinder charge to pulse time.
- A separate closed-loop fuel-pressure controller commands eMIOS PWM channel `0x4b`. It combines a
  calibrated pressure request, measured pressure difference, delivered-fuel demand, feed-forward,
  feedback, and a retained learned duty offset. The exact pump/module and electrical interface are
  not pin-confirmed.
- The oxygen-feedback cluster at `FUN_00a9B79C` runs twice, has extensive per-bank enable/fault
  gating, produces signed feedback values consumed by fueling, and exposes commanded lambda through
  OBD PID `0x44`.
- The purge subsystem is confirmed at the actuator boundary: it initializes and commands eMIOS PWM
  channel `0x56`, estimates purge flow, adds purge air to cylinder charge, and subtracts an estimated
  purge fuel contribution separately for both banks.
- Hard over-speed protection sets injection-cut state; loss of global injection enable substitutes
  the minimum pulse representation for both banks. Exact cylinder-skipping order for every torque
  or traction cut remains unresolved.

## Calibration-definition boundary

The RomRaider definition `8900689277A-emira.xml` is from the same MPC5777/64 KiB calibration family,
but its ID does not match analyzed target `8896915220A_ROW`. Its names are therefore used as naming
hypotheses only when code independently matches the address and shape. This constraint is documented
in `ROMRAIDER_DEFINITION_VALIDATION.md`.

The crosswalk strongly supports fuel table families at `0x0ABA`, `0x0F16`, `0x0F5E`, `0x0F6E`,
`0x0E86`, `0x4EBC`, `0x2DEA`, `0x7F94`, and `0x821C`. It labels `0x5B24` as an enrichment table, but
the code indexes it with a pressure difference and bounds its output like an injector-flow value.
For this report, code provenance takes precedence over that XML description.

## Runtime ordering

The phase scheduler places the important stages in this order across recurring wrappers:

```text
sensor conditioning / running state
        -> engine_load___() charge estimation
        -> purge estimation and purge-valve control
        -> dual-bank oxygen feedback
        -> injection() mixture, compensation, pulse formation
        -> cylinder injector event scheduling
```

`injection()` is called by `FUN_00a467c4` (`emira.c:59188-59210`), `engine_load___()` and purge
workers by `FUN_00a468f0` (`emira.c:59280-59303`), the slower purge accumulator by a later wrapper
(`emira.c:59518-59531`), and `FUN_00a9B79C` by `FUN_00a46588` (`emira.c:59038-59055`). This proves
recurring staged execution, but not an absolute rate without independently proving the scheduler
tick.

## Air acquisition and charge-source arbitration

### Conditioned air-path inputs

The sensor-conditioning path filters several pressure/temperature-like ADC quantities, substitutes
calibrated defaults under fault bits, and publishes the values consumed by charge estimation
(`emira.c:46070-46171`). Some inherited decompiler names are misleading: for example `load_` is
used in Kelvin-offset temperature arithmetic inside `engine_load___`, so its symbol name is not
evidence that it represents load (`emira.c:37657-37675`). Physical identities such as IAT, MAP,
barometric pressure, and fuel rail pressure require pin or transfer-function recovery.

### Two charge paths

`engine_load___()` (`0x00A1D7CC`, `emira.c:37568-37932`) constructs both:

1. a 16x16 modeled quantity using speed, an anonymous pressure/state input, calibration
   `CALBASE+0x6208` with axes `+0x61C8/+0x61E8`, the scalar at `+0x224`, and temperature-like
   density compensation (`emira.c:37645-37686`); and
2. a second air-path quantity using a 20x20 base table, the retained 20x20 correction surface at
   `0x400173A4`, sensor conversion factors, and another retained surface at `0x4001755C`
   (`emira.c:37704-37817`).

The retained surfaces default to the neutral multiplier 100 and are invalidated when their copied
calibration axes change (`FUN_00a12548`, `emira.c:31547-31651`; runtime lookups at
`emira.c:37708`, `37782`). Their exact OEM labels remain **inferred**; “long-term air/load
adaptation” is safer than asserting bank LTFT because both are consumed inside charge modeling.

### Selection and fallback

The final selector is explicit (`emira.c:37826-37835`):

- with the engine-running flag clear, or shared fault/inhibit bit 4 set, it chooses the alternate
  quantity `DAT_40006328` and marks fallback state `DAT_400036C0=1`;
- otherwise it chooses the pressure/temperature-modeled quantity `DAT_40006290` and clears the
  fallback marker.

The selected value is combined with an additional flow contribution and later clamped to 1380 as
`_load_unknown` (`FUN_00a1D0F0`, `emira.c:37318-37329`). It feeds injection directly and is
normalized for OBD absolute load with `CALBASE+0x16C` (`emira.c:37837-37887`). Thus its role as
cylinder air charge/load is **confirmed**, although exact units remain inferred.

### MAF/MAP conclusion

- **Confirmed:** two charge estimates, one pressure/temperature/VE-like; retained correction grids;
  explicit normal/fallback selection.
- **Inferred:** the normal path is consistent with speed density and the alternate with a
  throttle/air-path model.
- **Unknown:** whether either path contains a physical MAF measurement, which conditioned input is
  MAP or barometric pressure, and whether blending occurs in helpers outside this visible selector.
  No result in this pass justifies a “MAF” variable name.

## Injector characterization and pressure compensation

`injection()` starts by subtracting `DAT_40003772` from `DAT_400034AA`, clamping the result to
`0..65535`, and reducing it to a byte index (`emira.c:49778-49862`). The surrounding sensor
conversion and downstream use support **injector differential pressure** as a high-confidence
inference, but the two physical sensor names are not independently proved.

For each bank, the function then:

- indexes `CALBASE+0x5B24` with that differential-pressure quantity, clamps the result to
  `5000..20000`, and stores it as the base injector characterization (`emira.c:49972-49984`);
- applies a 20x20 operating correction at `CALBASE+0x0FCE`, axes `+0x0FA6/+0x0FBA`, bounded to
  `100..255`, and multiplies it into the base value (`emira.c:49957-49986`);
- divides selected cylinder charge by a bank-specific effective fuel/mixture denominator, with a
  floor of 800, to create a base delivered-fuel quantity (`emira.c:50042-50089`);
- converts the remaining required fuel through a bank-specific target denominator into pulse time
  (`emira.c:50090-50100`).

A common additive injector-time term is computed as `DAT_400036A0 * 50000 / characterized_flow`
(`emira.c:50310-50317`). This is consistent with voltage/latency compensation, but its input is not
named well enough to claim a conventional battery-voltage dead-time table. Final pulses also include
bank-specific multiplicative and signed additive corrections (`emira.c:50314-50386`).

Pulse formation has three visible limits per bank:

- minimum representation: 160;
- software ceiling: 270000;
- available cycle window: `DAT_40003424 * 8 - 250`.

Duty is published as 0..100%, and the limiting bank is inverted into injector-capacity load ceiling
`DAT_400032E0` for throttle/torque arbitration (`emira.c:50337-50402`, `50457-50489`).

### Fuel-pressure PWM control

`FUN_00a2b4e8()` initializes eMIOS channel `0x4b`; `FUN_00a2b5a8()` constructs its command
(`emira.c:45086-45280`). The controller:

- selects a calibrated pressure request and expresses it in 50-count steps;
- compares that request with measured pressure difference `DAT_400034a8`;
- estimates fuel demand from the injection-derived flow quantity and active-cylinder count;
- evaluates an 8x8 feed-forward map at `0xeb64` with axes `0xeb44/0xeb54`;
- adds bounded proportional/dynamic corrections and retained offset `0x40018372`;
- converts the result to a `0..10000` duty and writes the inverted hardware command to channel
  `0x4b`.

The adjacent worker `FUN_00a2bbec()` learns the retained offset only inside stable coolant, load,
speed, runtime, pressure-health, and diagnostic windows (`emira.c:45288-45397`). Its monitor family
submits internal indices `0x17`, `0x18`, `0x95`, and `0xd2` (`emira.c:45484-45564`). These are
fuel-pressure/control slots at subsystem confidence; their precise failure names and external P-codes
remain unknown.

This corrects a tempting but unsupported interpretation: channel `0x4b` is not the purge actuator.
Its direct pressure-error and delivered-fuel-demand dataflow establish fuel-pressure regulation.
Physical ownership—on-board driver, regulator valve, or external fuel-pump module command—requires
pad and harness evidence.

## Commanded mixture, lambda feedback, and trims

### Open-loop targets and protection enrichment

Each bank evaluates a separate 20x20 commanded-mixture table. Bank 0 uses the named
`CAL_inj_afr1`; bank 1 uses `CALBASE+0x77D6` with axes `+0x77AE/+0x77C2`. Both results are clamped
to encoded values `0xAA..0xE6`, and calibration byte `+0x5C` selects which target receives a common
offset (`emira.c:49987-50020`). The declared storage type is `u8_afr_1/20+5`, but stock-byte
validation is still needed before quoting real AFR or lambda values.

Additional confirmed enrichment/correction layers include:

| Calibration | Shape / condition | Direct behavior |
|---|---|---|
| `0x0F5E`, axis `0x0F56` | 8-point; periodically evaluated | magnitude used to construct a load-difference enrichment term (`emira.c:49799-49835`) |
| `0x0F6E`, axis `0x0F66` | 8-point | smoothing/blending weight for that term (`emira.c:49801-49835`) |
| `0x2716`, axis `0x270E` | 8-point | converts the smoothed term to a bounded common correction (`emira.c:49837-49858`) |
| `0x0ABA`, axis `0x0AB6` | 4-point | `50..200` multiplier enabled by calibration mode byte `+0x73` (`emira.c:49929-49944`) |
| `0x52A8` / `0x18C6` | 16-point, transmission variant | additive coolant-dependent correction (`emira.c:49945-49956`) |
| `0x2DEA` or `0x2102` | 12x14 operating branch | coolant/runtime correction before final pulse (`emira.c:50147-50185`) |
| `0x7F94` / `0x821C` | 12x24, manual/IPS branch | alternate startup/runtime enrichment (`emira.c:50165-50199`) |
| `0x05AE` | 10x10 | further load/state multiplier, bounded around neutral 100 (`emira.c:50199-50223`) |

These layers prove warm-up, power/operating, and state-dependent enrichment. Which layer is the OEM
“component protection” or catalyst-temperature enrichment is **unknown**; the export does not tie a
named exhaust-temperature state to a particular table strongly enough.

### Closed-loop feedback

`FUN_00a9B79C` (`0x00A9B79C`, `emira.c:93407-93505`) iterates exactly twice. For each bank it:

- gathers measured-versus-commanded feedback and operating state;
- rejects feedback under a large bank-specific set of DTC and inhibit conditions;
- applies temperature, runtime, load, and sensor-readiness gates;
- runs a controller and stores a signed correction at `0x40013B86 + bank*2`.

`FUN_00a9BB40(bank)` returns that correction (`emira.c:93519-93530`). `injection()` uses it as a
signed per-bank multiplicative contribution to pulse time (`emira.c:50314-50322`, `50365-50372`).
This is **confirmed closed-loop bank correction**. The exact normalization of `0x7FFF`, controller
gain units, and whether service tooling calls it STFT remain unproved.

The bank loop also calls `FUN_00a9CB44`, which maintains a 100-sample history and an operating-point
dependent filtered value (`emira.c:94138-94206`). This proves a filtered/adaptive feedback signal;
calling it fuel-film state or LTFT without further writer tracing would be premature.

When feedback is active, `injection()` can subtract a bank-specific 8x8 quantity:

- bank 0: `0x0E86`, axes `0x0E66/0x0E76`;
- bank 1: `0x4EBC`, axes `0x4E9C/0x4EAC`.

The subtraction and enable test are direct (`emira.c:50048-50074`). The XML calls these “lambda
trim”; their exact physical meaning and scaling remain **inferred**.

Finally, `FUN_00a33ADC(bank)` returns the bank result/state pair and OBD Mode 01 PID `0x44` converts
its upper half to commanded lambda (`emira.c:49693-49706`, `84636-84654`). This independently
confirms that the bank computation carries commanded-mixture state, not merely injector time.

## Bank handling and learned correction

Bank separation is pervasive:

- the main injection construction loop runs for indices 0 and 1 (`emira.c:49854-50102`);
- `FUN_00a338C0` selects distinct 8x8 maps at `0x0A26` and `0x0A76` based on bank selector 1/2
  (`emira.c:49616-49659`);
- closed-loop state, filtered feedback, additive corrections, pulse width, duty, and injector
  capacity are maintained independently;
- six final injector triggers are scheduled at evenly spaced 720-degree-cycle positions
  (`emira.c:50436-50456`).

Persistent fuel/air adaptation is also confirmed, but its naming is incomplete. Two retained 20x20
neutral-100 grids alter charge estimation, while learned per-bank correction fields and tables in
the `0x400176xx..0x40017Exx` range enter final fueling. Startup resets these selectively when
calibration axes or identity change (`LEARNED_DATA_PERSISTENCE_ANALYSIS.md`; `emira.c:31547-31651`).
The code proves persistence and bank use; it does not prove the conventional STFT/LTFT label for
every retained field.

## Purge air and purge fuel

The purge identification is high confidence because the complete actuator-to-fuel chain is visible:

- `FUN_00a3D7B0` initializes eMIOS PWM channel `0x56` using calibrated frequency `CALBASE+0x122`
  (`emira.c:54505-54513`).
- `FUN_00a3D7F8` / `FUN_00a3E288` implement a multi-state enable, flow-learning, fault, ramp, and
  diagnostic sequence, with separate bank feedback participation (`emira.c:54515-54964`).
- The final duty is rate-shaped, sent to channel `0x56`, and published through a diagnostic helper
  (`emira.c:54933-54964`).
- Estimated purge flow `DAT_4000380A` is calculated from valve command (`emira.c:54947-54958`).
- Charge estimation multiplies this flow by a learned/concentration-like term to form
  `DAT_400036EA`, then adds it to the selected cylinder charge (`emira.c:37792-37793`,
  `37826-37836`).
- `FUN_00a3D418(bank)` derives a separate bank fuel-displacement quantity from closed-loop feedback,
  purge flow, and total charge, publishing it at `DAT_40003806 + bank*2` (`emira.c:54392-54466`).
- `injection()` subtracts that estimated delivered purge fuel before converting remaining fuel demand
  to pulse time (`emira.c:50078-50100`).

Thus purge is not only a valve command: both its added air and hydrocarbon/fuel contribution are
explicitly integrated into the fueling equation. Exact units and the physical bank distribution are
still unknown.

## Transient fuel / wall-film boundary

`FUN_00a33A04` (`0x00A33A04`) evaluates the 8x8 table at `0x0F16`, axes `0x0F06/0x0F0E`, from an
operating input and a signed delta clamped to `-512..508`. It scales that delta, then tapers the
result between calibrated thresholds `+0x1D6` and `+0x13C` (`emira.c:49667-49720`). `injection()`
calls it independently for the two banks and adds its signed output to final pulse time
(`emira.c:50323-50333`, `50376-50386`).

This is **confirmed transient additive fuel** and agrees with the XML label “transient enrichment.”
However, a physical intake-wall wetting/evaporation state equation has not been isolated. Therefore:

- tip-in/tip-out transient compensation is confirmed;
- calling it a complete wall-film model is inferred;
- the roles of adjacent retained values `_DAT_400176FC/FE` and `_DAT_40017E0C/0E` require more
  writer-level tracing before assigning deposit and evaporation masses.

## Protection, saturation, and fuel cuts

Confirmed protection boundaries are:

1. **Mixture and correction bounds.** Target bytes, injector characterization, enrichment factors,
   additive terms, and pulse widths are repeatedly clamped before output.
2. **Injector-capacity protection.** Pulse is limited to the cycle window; the limiting bank creates
   a load ceiling consumed by throttle arbitration (`emira.c:50337-50402`, `50457-50489`).
3. **Hard rev cut.** At or above `revlimit_hard_cut_rpm`, `injection()` sets bits `0`, `3`, and
   conditionally `5` in `DAT_400034C0`; recovery uses calibrated hysteresis at `CALBASE+0x1CC`
   (`emira.c:50406-50427`). These are injection-cut/status bits consumed elsewhere in cylinder-event
   logic.
4. **Global injection inhibit.** If `DAT_4000377C & 1` is clear, both published bank pulses are
   forced to 160 and inhibit bit `0x10` is set; otherwise calculated bank pulses are published
   (`emira.c:50428-50436`).
5. **Per-cylinder event masks.** Six cylinder enable bytes are initialized and altered during sync
   and operating transitions (`emira.c:29197-29310`), and the cylinder event writer selects bank
   pulse width by cylinder (`emira.c:27957-27961`). Their complete torque/traction/catalyst-cut
   policy is not yet decoded.

The report does **not** claim that every protection uses enrichment. Spark, throttle, purge disable,
and cylinder cut are separate authorities elsewhere in the application.

## Confirmed / inferred / unknown summary

### Confirmed

- dual-path charge calculation and explicit fault fallback;
- retained neutral-100 charge-adaptation surfaces;
- two bank target maps and two closed-loop feedback controllers;
- pressure-indexed injector characterization and operating correction;
- closed-loop fuel-pressure control on PWM channel `0x4b`, including retained duty learning;
- bank-specific base fuel, additive correction, pulse, duty, and capacity limit;
- purge PWM channel `0x56`, purge-air addition, and bank purge-fuel subtraction;
- bank transient additive fuel via `0x0F16`;
- hard-rev injection cut, global injection inhibit, and six-cylinder scheduling;
- commanded-lambda reporting through OBD PID `0x44`.

### Inferred

- normal charge path is speed-density/VE-like;
- alternate charge path is a learned throttle/air-path model;
- `DAT_400034AA - DAT_40003772` is injector differential pressure;
- several retained fields are long-term bank fuel/air corrections;
- `FUN_00a33A04` represents a wall-film-oriented transient correction.

### Unknown

- whether a physical MAF exists in either charge path and its channel/transfer function;
- exact MAP, barometric, rail-pressure, and intake-temperature variable identities;
- physical fuel-pump/regulator module and active polarity behind channel `0x4b`;
- engineering units for cylinder charge, injector characterization, and all trim values;
- which enrichment layer is specifically catalyst, exhaust-temperature, or component protection;
- exact STFT/LTFT naming and learning rates for every persistent field;
- complete per-cylinder cut order and the division of torque intervention among fuel, spark, and
  throttle;
- byte-for-byte compatibility of the `8900689277A` XML with `8896915220A_ROW`.

## Highest-value next steps

1. Recover the exact application/calibration binary and validate all fuel table bytes, axes, and
   encodings against `8896915220A_ROW`.
2. Trace ADC transfer functions and fault bits for the pressure/temperature inputs to decide MAP,
   barometric, rail-pressure, and possible MAF identities.
3. Trace every writer of `0x400176FC/FE`, `0x40017E0C/0E`, and the bank adaptation tables to separate
   STFT, LTFT, transient deposit/evaporation, and injector-offset learning.
4. Correlate logged commanded lambda, bank feedback, purge duty/flow, pressure difference, pulse
   width, and charge estimates during purge-on/off and tip-in tests.
5. Trace `DAT_400034C0` and the six cylinder event masks through the final injector driver to produce
   an exact cut-authority and cylinder-order report.
