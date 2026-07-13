# Emira ignition and knock analysis

Target: 2022 Lotus Emira V6 ROW firmware `8896915220A_ROW`.

This report extends `POWERTRAIN_CONTROL_ANALYSIS.md` by recovering the previously anonymous knock
cluster. It traces sampling-window configuration, the signed sample ring, four frequency-analysis
modes, the visible detector/retard handoff, persistent six-cylinder learning, and final ignition use.

## Executive findings

- The `enum_knock_mode` declaration is backed by four real DSP implementations, selected by the
  calibration byte at `CALBASE+0x28e`.
- Eleven calibrated frequency/bin values at offsets `0x26e..0x282` are filtered and sorted against a
  sampling-rate/count value at `0x28c`.
- A signed 128-sample circular buffer begins at `0x40007b48`; the current index is
  `DAT_4000817d`, and `DAT_40007b40` marks a completed buffer for DSP consumption.
- A periodic interrupt source at `0xc3fa0100`, vector `0x42`, priority `15`, is configured from the
  calibration at `0x28c`. The installed handler label is not decompiled, so the exact analog/DMA
  source that fills the ring remains unresolved.
- The four modes structurally match 64-sample Goertzel, dual-window Goertzel, 128-point spectral,
  and overlapping 32-sample Goertzel processing.
- Mode 4 has the only explicit detector callback preserved by the export. Modes 1–3 calculate and
  publish energy but their final consumer is not visible.
- Six per-cylinder event-retard values and six longer-term/fallback corrections are consumed directly
  by the ignition calculation.
- Six persistent 16-bit learned values at `0x400176f0..0x400176fb` are part of the learned image
  (`0x4001735c + 0x394`) and are adjusted under knock-learning gates.

The firmware therefore has a full knock acquisition/DSP/correction architecture. What is still not
proved is the physical sensor/channel assignment, the missing ISR's sample normalization, and whether
the stock ROW calibration selects mode 4.

## Scheduling and pipeline

```text
periodic angle/sample interrupt (vector 0x42; body absent)
  -> signed sample ring 0x40007b48[128]
  -> completed-buffer flag DAT_40007b40

500 Hz-consistent wrapper FUN_00a46100
  -> gain/range selection FUN_00a6dd90
  -> RPM/load window and six cylinder angles FUN_00a6deec
  -> mode 2 DSP FUN_00a6d3b8
  -> mode 1 DSP FUN_00a6ca84
  -> mode 4 DSP FUN_00a6cdc4
  -> mode 3 DSP FUN_00a6d5a0

200 Hz-consistent wrapper FUN_00a46408
  -> hold/decay and persistent learning FUN_00a6e324

100 Hz-consistent wrapper FUN_00a46668
  -> detection/correction arbitration FUN_00a6e6c0

slow frame wrapper FUN_00a46fe0
  -> frequency-bin normalization/reset FUN_00a6c9e0

ignition aggregation FUN_00a92e60 / FUN_00a92f30
  -> both six-cylinder correction arrays
  -> six final ignition events via FUN_00a93adc
```

The relative scheduler groups come from `RUNTIME_ARCHITECTURE_ANALYSIS.md`; the absolute 1 ms base is
strongly supported but still awaits direct recovery of the scheduler tick ISR.

## Frequency configuration

`FUN_00a6c8e4()` (`emira.c:71775-71833`) constructs the frequency-bin list:

- eleven 16-bit calibration pointers are installed for offsets `0x26e`, `0x270`, `0x272`, `0x274`,
  `0x276`, `0x278`, `0x27a`, `0x27c`, `0x27e`, `0x280`, and `0x282`;
- each requested value is discarded if it exceeds the sampling parameter at `0x28c`;
- `FUN_00a16090()` sorts the eleven values;
- zero entries are compacted out;
- the surviving count is stored in `DAT_4000817c`.

`FUN_00a6c9e0()` (`emira.c:71835-71873`) converts the surviving values to normalized bin/step bytes
and clears the energy/output arrays when configuration state changes. The 11-entry design matches the
Evora frequency-bin count but the actual Emira frequencies cannot be calculated until the clock and
stock calibration bytes are available.

## Sampling window and gain selection

### Periodic acquisition source

`FUN_00a6c884()` (`emira.c:71760-71773`) programs an eMIOS-like periodic source at
`0xc3fa0100`, installs `LAB_00a6c690` at vector `0x42`, and assigns priority `0x0f`. Its period is
derived from constant `0x1e8480` and calibration `0x28c`.

The label body is not rendered as C. The sample ring and buffer-ready flag have no ordinary C writer,
which is consistent with the missing interrupt body or a DMA/event producer. This report does not
claim an ADC module, DMA channel, midscale, or bank-to-sensor mapping without that body.

### RPM/load-dependent window

`FUN_00a6deec()` (`emira.c:72770-72821`) performs two 16x16 RPM/load lookups:

- table `0x240e`, axes `0x23ee/0x23fe`, producing the common window angle;
- table `0x252e`, axes `0x250e/0x251e`, producing a bounded sample/window-size value clamped to
  `0x40..0x80`.

The common angle is added to six fixed per-cylinder offsets at `0x00ab9498`, plus 5.0 degrees, then
wrapped over `0x1c20` (720.0 degrees). The six scheduled centers are stored at
`0x4001c416..0x4001c421`. This proves cylinder-phased acquisition even though the final low-level
trigger channel is not named.

### Gain/range state

`FUN_00a6dd90()` (`emira.c:72728-72768`) selects four hysteretic states from an RPM-like input and
three calibration thresholds at offsets `0xc6..0xc8`, with band `0xc9`. It publishes two logical
range-select bits and reference values `0xff`, `0x80`, `0x41`, or `0x21`. This is characteristic of
the four-range knock front-end normalization also seen in Evora.

Low-level routines around `FUN_00a6d788()` and `FUN_00a6d900()` configure and service hardware at
`0xfff90000`, `0xfff94000`, `0xfff98000`, and associated `0xc3f9xxxx` outputs. Their precise division
between knock gain, ADC setup, and other serial/analog functions remains partially anonymous, so the
physical AGC GPIO assignment is not claimed.

## Four DSP modes

The calibrated selector `*(CALBASE_addr + 0x28e)` is tested directly by every mode worker.

| Value | Enum name | Worker | Confirmed structure |
|---:|---|---|---|
| `1` | `goertzel_64sample` | `FUN_00a6ca84()` | 64-sample recurrence for each selected bin, plus a second/end-window recurrence; squared-energy outputs |
| `2` | `goertzel_2window` | `FUN_00a6d3b8()` | two 64-sample sine/cosine accumulation windows; maximum energy retained per bin |
| `3` | `spectral_128window` | `FUN_00a6d5a0()` | copies 128 ring samples, performs staged complex transforms, and records 128-bin magnitude maxima |
| `4` | `goertzel_32sample_x3` | `FUN_00a6cdc4()` | multiple 32-sample recurrences plus overlapping/end windows; takes the maximum energy and calls the detector handoff |

Source anchors are `emira.c:71875-72008`, `72010-72238`, `72240-72318`, and `72320-72402`.
All workers clear `DAT_40007b40` after consuming the completed ring.

Mode 4 calls the unresolved symbol `func_0x00a6e040(mode_or_bin, energy)` at
`emira.c:72233`. That callee's body is absent from the export. It is the strongest candidate for the
energy-normalization/baseline/detection handoff because downstream per-cylinder event magnitudes at
`0x4000820c` are consumed and cleared by `FUN_00a6e6c0()`.

Modes 1–3 publish energy arrays around `0x40008180` and `0x400081ac`, but no final detector call is
preserved. Possible explanations are dormant development algorithms, a lost indirect reference, or
external observation/logging. Stock calibration byte `0x28e` is required before identifying the
production mode.

## Detection gates and per-cylinder corrections

`FUN_00a6e6c0()` (`emira.c:72996-73159`) builds the correction state:

- RPM/load threshold and hysteresis tables at `0x2a6e`, `0x2b16`, and `0x2b26` establish detection
  and learning regions;
- transient/fault masks in `DAT_40003818`, `DAT_40003815`, and `DAT_400038ea` inhibit detection or
  learning;
- the maximum six-cylinder learned value is tracked for fallback/arbitration;
- each nonzero event magnitude in `0x4000820c[cyl]` is converted through the eight-point table at
  `0x2b06` with axis `0x2afe`;
- the resulting per-cylinder event correction at `0x400038e4[cyl]` is capped by calibration
  `0x92`;
- a second per-cylinder correction at `0x400038dc[cyl]` combines the base-to-safe timing gap with
  persistent learned quality and fault fallback;
- the six second-layer values are summed into `DAT_400038ec` for average/diagnostic use.

The precise physical units are not named, but the downstream ignition consumer and byte arithmetic
support a timing-retard interpretation. The two arrays are best described as event/learned retard and
longer-term/fallback knock correction until raw calibration scaling is verified.

## Persistent learning and recovery

`FUN_00a6e324()` (`emira.c:72823-72930`) provides two time bases:

- every ten invocations it holds or decays six per-cylinder event-retard values and adjusts the six
  persistent 16-bit values;
- every hundred invocations it decays a second six-byte correction/hold array.

The persistent values begin at `0x400176f0`. This is exactly
`learned_image_base 0x4001735c + 0x394`, so they are saved in the `0x3f94`-byte learned image and
survive key cycles. Each value:

- increases by calibration byte `0x95` when event correction and an energy/baseline threshold are
  exceeded;
- decreases by calibration byte `0x96` below a lower threshold;
- saturates at `0xffff` and floors at zero.

Calibration `0x94` controls decay of the six event corrections, while `0xe0/0xe1` control a separate
hold/decay layer. This is the structural equivalent of persistent per-cylinder octane/knock-quality
learning, although the OEM symbol and exact “higher means more/less octane” direction are not yet
proved.

Startup reset code at `emira.c:31866-31871` clears all six learned values as part of a broader
learned-repair state machine. `LEARNED_DATA_PERSISTENCE_ANALYSIS.md` should be used for the
image-level compatibility and reset policy.

## Final ignition integration

The two six-byte arrays are exported by:

- `FUN_00a6eb8c()` / `FUN_00a6ebcc()` from `0x400038e4`;
- `FUN_00a6ebac()` / `FUN_00a6ebf0()` from `0x400038dc`.

`FUN_00a92e60()` (`emira.c:91020-91069`) retrieves both arrays, combines the first with the active
manual/IPS base timing, and calculates the average margin to a conservative reference map.
`FUN_00a92f30()` retrieves both again at `emira.c:91213-91214` before producing six final cylinder
angles. `FUN_00a93adc()` then writes each final angle to its eTPU ignition event channel.

This proves the knock results affect commanded combustion timing; they are not diagnostics-only DSP
outputs.

## Knock-related diagnostics

The large diagnostic routine `FUN_00a35b9c()` (`emira.c:50777-51198`) consumes knock state,
per-cylinder learned/event values, engine speed/load gates, and DTC enable calibrations. It submits
internal DTC indices including `0x87` and `0xc4`, among others. Because internal indices are separate
from external P-codes, this report does not label them as particular knock-sensor or threshold codes.

The diagnostic logic distinguishes:

- missing/invalid event activity;
- failure to learn or decay under eligible conditions;
- sensor/front-end state and threshold plausibility;
- calibration-enabled bank/system monitor results.

Exact external codes require exporting the 258-entry DTC dictionary described in
`DIAGNOSTIC_MONITOR_FRAMEWORK_ANALYSIS.md`.

## Evora comparison

Confirmed common concepts:

- four configurable DSP modes with the same enum values;
- up to eleven frequency bins;
- 64–128-sample acquisition windows;
- RPM/load-dependent window angle and length;
- four-range front-end normalization;
- per-cylinder event retard plus persistent octane-quality learning;
- final use in per-cylinder ignition scheduling.

Important Emira-specific boundaries:

- Emira uses different addresses, scheduler structure, hardware setup, and cylinder event channels.
- The Emira acquisition ISR/body and physical bank/sensor selection are missing from this C export.
- The visible mode-4 detector callback has no decompiled body.
- No stock calibration bytes are present to prove the active DSP mode.

The Evora result was useful for recognizing expected architecture, but the function cluster,
calibration offsets, buffers, modes, scheduling, and ignition consumers above are independently
present in Emira code.

## Confirmed, inferred, and unknown

### Confirmed

- 11-entry frequency configuration and 128-sample signed ring.
- Periodic vector `0x42` acquisition trigger and six cylinder-phased window centers.
- Four DSP workers selected by calibration values 1–4.
- Goertzel/spectral recurrence structure and mode-specific sample lengths.
- Mode-4 energy-to-detector call and downstream per-cylinder event magnitudes.
- Two six-cylinder correction arrays consumed by final ignition.
- Six persistent learned values inside the learned-data image.
- Hold, decay, increment, decrement, saturation, and fault-fallback behavior.

### Strong inference

- The ring contains normalized knock-sensor samples.
- `0x400038e4` is short/event knock retard and `0x400038dc` is octane/fallback correction.
- Learned-image offset `0x394` is a six-cylinder octane-quality scaler.
- The four hysteretic range states control knock front-end gain.

### Unknown

- Exact ADC/serial source, DMA channel, sample scaling, and midscale subtraction.
- Physical sensor count and cylinder-bank assignment.
- Exact eTPU/eMIOS channel that opens and closes the acquisition window.
- Body and normalization policy inside missing `func_0x00a6e040`.
- Stock `CALBASE+0x28e` value and therefore active production DSP mode.
- Engineering-unit scaling and higher/lower meaning of persistent quality values.
- External DTC identities for knock-related internal indices.

## Highest-value next steps

1. Recover `LAB_00a6c690` and `func_0x00a6e040` at instruction level from a canonical binary/Ghidra
   project.
2. Extract stock calibration offsets `0x26e..0x28e`, `0x23ee..0x252e`, and
   `0x2a5e..0x2b26` to prove frequencies, window sizes, thresholds, and active mode.
3. Trace the periodic trigger through SIUL2/eMIOS/eQADC/eDMA configuration to physical knock inputs.
4. Log ring samples, energy, event magnitude, both correction arrays, and persistent values during a
   controlled knock-free/knock stimulus test.
5. Export the DTC dictionary before assigning P-codes or bank/sensor names.
