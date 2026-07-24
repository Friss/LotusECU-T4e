# Emira Learned-Data Persistence Analysis

## Scope and confidence convention

This report describes the nonvolatile **learned/runtime image** used by Emira ECU image `8896915220A_ROW`: its physical representation, integrity and compatibility checks, recovery hierarchy, principal consumers, and save path.

The separate coding/configuration block at flash `0x00030000` is discussed only to establish the boundary between the two stores. It is not part of the learned image.

Confidence labels are used throughout:

- **Confirmed** — directly supported by memory accesses and control flow in `emira.c`.
- **Inferred** — the data flow is clear, but the physical meaning or naming is not fully recoverable from the decompilation.
- **Unknown** — no defensible field-to-domain mapping has yet been established.

## Executive summary

The ECU maintains one contiguous `0x3f94`-byte retained image in RAM at `0x4001735c..0x4001b2ef` and mirrors it to flash at `0x00010000`. On startup it reads the entire image, validates two nested CRC domains plus size and program/calibration identity, then selects among:

1. full default reconstruction for an incompatible core/program image;
2. broad diagnostic/history repair for a compatible core but damaged outer image;
3. narrower calibration-identity repairs for individual learned tables; or
4. normal reuse plus per-record diagnostic validation.

The image includes fuel/air correction grids, throttle-stop learning, idle-related retained offsets, six confirmed per-cylinder knock-learning values, several not-yet-fully-identified adaptation tables, and OBD/DTC history and snapshot banks. A persistent VVT correction has not been established. The knock domain's structure and consumer chain are confirmed, although its exact OEM scaling and correction direction remain inferred.

Runtime code changes the RAM image in place. No single whole-image dirty bit or incremental flash journal was found. At shutdown or an explicit reset path, the ECU recomputes both CRCs, disables external interrupts, erases/programs the whole flash image, pads the last flash phrase with `0xff`, and performs flash-driver teardown. There is no visible second slot, read-back verification, retry, or propagated program error; recovery from an interrupted/failed write occurs at the next startup through the validation/default hierarchy.

## Physical layout and integrity envelopes

| Item | Address/range | Size | Confidence | Evidence |
|---|---:|---:|---|---|
| Learned-image flash source | `0x00010000` | `0x3f94` bytes | Confirmed | `FUN_00a11b0c()` erases/programs `0x10000` (`emira.c:30692-30711`); startup copies `0x3f94` bytes into RAM (`emira.c:31397`). |
| Learned-image RAM mirror | `0x4001735c..0x4001b2ef` | `0x3f94` bytes | Confirmed | Startup copy and save calls (`emira.c:31397`, `30951`). |
| Core integrity domain | `0x4001735c..0x400189c3` | `0x1668` bytes | Confirmed | CRC calculated over `0x1668`; size and CRC stored at `0x400189c0` and `0x400189c4` (`emira.c:30944-30945`, `31401-31408`). The stored CRC lies immediately after the covered bytes. |
| Full integrity domain | `0x4001735c..0x4001b2eb` | `0x3f90` bytes | Confirmed | CRC calculated over `0x3f90`; size and CRC stored at `0x4001b2e8` and `0x4001b2ec` (`emira.c:30947-30948`, `31402-31424`). The size is inside the covered range; the CRC is outside it. |
| Program/header identity | `0x4001735c..0x4001737b` | `0x20` bytes | Confirmed as identity data; exact format unknown | Compared with canonical `DAT_00ab0fb0` and overwritten with that value after validation (`emira.c:31427-31430`, `31495-31502`). |
| Program/version identifier | `0x400189bc` | at least 2 bytes | Confirmed as compatibility identifier | Compared with `DAT_40003286`, then updated to the current value (`emira.c:31428-31435`, `31503`). |
| Full-image size field | `0x4001b2e8` | 4 bytes | Confirmed | Written as `0x3f94` before save and checked at startup (`emira.c:30947`, `31423-31425`). |
| Calibration/variant identities | near `0x4001b0fd`, plus `0x4001b1e4` | several markers | Confirmed as selective compatibility markers; detailed formats inferred | Compared against current calibration/variant bytes to selectively reset dependent data (`emira.c:31457-31488`, `31504-31545`, `31753-31770`). |

The two CRCs form nested recovery domains. The `0x1668` core covers the image header and the earlier adaptation region through `0x400189c3`. The `0x3f90` CRC covers nearly the entire image, including the core metadata, diagnostic history, snapshots, and trailing state, stopping immediately before its stored CRC. This structure permits the firmware to distinguish a damaged/incompatible adaptive core from damage confined to the later history/diagnostic portion.

## Startup validation and recovery hierarchy

### 1. Load and classify integrity failures

`FUN_00a12548()` marks startup entry in `DAT_40003458`, reads the whole image, then computes both CRCs (`emira.c:31396-31403`). It compares:

- core CRC and recorded core length `0x1668`;
- full CRC and recorded image length `0x3f94`;
- the 32-byte canonical header;
- the program/version value at `0x400189bc`.

Observed reason bits in `DAT_40003458` include `0x4000` for a core CRC failure, `0x8000` for a full CRC failure, `0x4` for header mismatch, and `0x8` for program/version mismatch (`emira.c:31404-31435`). Other reset helpers OR further reason bits into this same accumulator. **Confirmed:** this is a startup/reset-reason bitmap. It is not the learned-image dirty flag.

### 2. Incompatible core or program: reconstruct defaults

If the program/version differs or the core length/CRC is incompatible, startup sets `DAT_40003456 = 1` and invokes a hierarchy of reset helpers (`FUN_00a12084`, `FUN_00a124d0`, `FUN_00a11cfc`, `FUN_00a123e8`) while clearing a large retained/history region (`emira.c:31435-31452`).

`DAT_40003456` means that default reconstruction is in progress. The stepped worker `FUN_00a12c10()` then rebuilds sizeable tables over multiple calls:

- a bank beginning at `0x40017700` from one of two calibration-dependent constant sets (`emira.c:31752-31781`);
- six 153-byte tables at `0x40017a04`, `0x40017a9e`, `0x40017b38`, `0x40017bd2`, `0x40017c6c`, and `0x40017d06` (`emira.c:31783-31808`);
- six 17-byte arrays at `0x40017da0..0x40017e0a`, initialized to 10 (`emira.c:31810-31825`);
- eight groups of six 16-bit values in the region beginning `0x40017e14`, initialized to 2000 (`emira.c:31827-31850`);
- both 20-by-20 learned correction grids and their current calibration axes (`emira.c:31852-31860`);
- further scalar/auxiliary domains through reset helpers, before clearing the reconstruction flag (`emira.c:31862-31877`).

Save paths explicitly avoid writing while `DAT_40003456` is set (`emira.c:65046-65047`, `65075-65076`, `92633-92634`, `103992-103993`). This prevents a partly reconstructed image from being committed.

### 3. Valid core, damaged/incompatible outer image: broad history repair

If the program/core domain is usable but the header or full domain is not, startup calls `FUN_00a123e8()` without requesting complete core reconstruction (`emira.c:31454-31455`). That helper chains diagnostic/readiness, history, catalyst/O2, and throttle-related reset helpers and records reset-reason bits (`emira.c:31280-31332`).

**Confirmed behavior:** compatible earlier adaptation can survive damage detected only by the outer integrity/identity checks. **Inferred rationale:** the core CRC boundary exists specifically to preserve expensive adaptation while discarding later diagnostic/history state that cannot be trusted.

### 4. Valid image, calibration or variant changed: selective repair

With the structural checks valid, startup performs more granular compatibility checks:

- `DAT_4001b1e4` is checked against calibration byte `CALBASE+0x4412`; mismatch calls `FUN_00a703e8()` to restore a set of calibration-dependent tables (`emira.c:31457-31460`).
- An 8-byte variant identity near `0x4001b0fd` is checked against one of two calibration locations, selected by `FUN_00a73414()`; mismatch clears dependent catalyst/history state (`emira.c:31504-31545`).
- The 40-byte signature/axes at `0x4001737c` is compared with `CALBASE+0x279e`; mismatch resets the associated 400-byte surface at `0x400173a4` to 100 (`emira.c:31547-31567`).
- A second 40-byte signature/axes at `0x40017534` is compared with current calibration data; mismatch resets the surface at `0x4001755c` to 100 (`emira.c:31569-31589`).

These are domain-local invalidations: calibration changes do not automatically force all learned data back to defaults.

### 5. Record-level validation after image-level validation

The diagnostic manager subsequently calls `FUN_00a858cc(1)` (`emira.c:83657`). That function validates/normalizes the 258-entry compact and auxiliary DTC stores, the 21-entry snapshot bank, and the ten-entry event bank, then reconciles retained records against the current monitor catalog (`emira.c:82669-82713`). Thus a valid whole-image CRC does not prevent semantic validation and repair of individual diagnostic records.

## Mapped learned domains

### Fuel/air adaptive correction surfaces — confirmed

Two retained two-dimensional correction surfaces are strongly established:

| Domain | Axes/signature | Values | Default | Evidence |
|---|---:|---:|---:|---|
| Surface A | `0x4001737c`, 40 bytes | `0x400173a4`, 400 bytes (`20 x 20`) | 100 | Calibration compatibility/reset (`emira.c:31547-31567`, `31852-31855`); runtime lookup (`emira.c:35873`, `37708`). |
| Surface B | `0x40017534`, 40 bytes | `0x4001755c`, 400 bytes (`20 x 20`) | 100 | Calibration compatibility/reset (`emira.c:31569-31589`, `31857-31860`); runtime lookup (`emira.c:35215`, `35962`, `37782`). |

The value of 100 is the neutral multiplicative correction. Both grids are consumed by interpolated 20-by-20 lookups in fuel/air/load calculation paths. **Confirmed:** these are persistent adaptive correction grids. **Inferred:** they are banked long-term fuel/load corrections. The exact service-tool labels, axes, and whether each is specifically “LTFT bank 1/2” remain unknown.

Additional calibration-dependent retained table banks at `0x40017700..0x40017e0a` are confirmed by their reconstruction and transfer into runtime arrays (`FUN_00a12c10`, `FUN_00a702cc`). Six values within that span are now assigned to knock learning below; the exact roles of the six 153-byte tables and several adjacent arrays remain unproved.

### Electronic throttle learning — confirmed

Throttle-stop/end-point data is retained around `0x4001b0f0`:

- `FUN_00a25334()` validates learned endpoint pairs and derives usable throttle geometry (`emira.c:41827-41865`; callers at `42921`, `42984`).
- `FUN_00a254a0()` records learned endpoints and context at `0x4001b0f0..0x4001b0f9`, while also updating related retained event/fault history in the `0x400181c0..0x40018370` region (`emira.c:41871-42020`).
- Startup reset helpers `FUN_00a12084()` and `FUN_00a124d0()` clear or restore this learning/history when compatibility demands it (`emira.c:31051-31100`, `31335-31368`).

This domain is more than two stop values: it includes the endpoint pair, the conditions/context under which it was learned, and retained fault/event evidence used by the ETB safety logic.

### Idle adaptation — confirmed as retained input; exact field semantics inferred

Retained values including `0x40017a00`, `0x40017a02`, `0x40017e10`, and `0x40017e12` are interpolated into idle-control calculations in `FUN_00a30f20()` and related idle logic. They are initialized through `FUN_00a12bc0()` during full reconstruction (`emira.c:31863-31866`).

**Confirmed:** idle control consumes persistent, temperature/mode-dependent learned offsets. **Inferred:** these represent learned idle airflow/torque or speed corrections. Exact units and assignment of each scalar to manual/automatic or hot/cold operation remain to be named.

### Diagnostic trouble-code history and snapshots — confirmed

The outer part of the learned image contains substantial retained diagnostic state:

| Structure | Base | Shape | Established role |
|---|---:|---:|---|
| Compact monitor records | `0x40018b7e` | 258 × 4 bytes | Monitor/DTC identifiers and compact status/history. |
| Auxiliary monitor records | `0x40019490` | 258 × `0x16` bytes | Per-monitor auxiliary occurrence/status data. |
| Snapshot bank A | `0x4001aabc` | 21 × `0x34` bytes | Retained snapshot/freeze-frame-like records. |
| Snapshot bank B | `0x4001af00` | 21 × `0x34` bytes | Second retained snapshot class/bank. |
| Compact event bank | `0x4001b004` | 10 × 10 bytes | Small retained event/record queue. |

The shape and bounds are visible in record access, clearing, and validation loops (`emira.c:77243`, `77425-77457`, `77770`, `78480-78539`, `78904-78918`, `79295-79328`, `82682-82713`). The exact legislated/service nomenclature for the two snapshot banks remains partly inferred, but their persistence and association with the DTC manager are confirmed.

### VVT learning — unknown

The VVT controllers consume live and calibration data, but this pass did not establish a stable address-level link from a retained field in `0x4001735c..0x4001b2ef` to a VVT learned correction. VVT learning may exist within one of the unidentified table banks, but labeling any of them as VVT adaptation would currently be speculative.

### Knock learning — confirmed six-value retained domain

Six retained 16-bit values at `0x400176f0..0x400176fb` (learned-image offset `0x394`) form a per-cylinder knock-learning domain. Full reconstruction clears all six (`emira.c:31866-31871`). `FUN_00a6e324()` updates them using calibration bytes at offsets `0x95` and `0x96`; `FUN_00a6e6c0()` consumes them while constructing the six-cylinder knock correction state (`emira.c:72823-73135`). The final ignition paths then consume the resulting correction arrays through `FUN_00a6eb8c()` and `FUN_00a6ebac()` (`emira.c:91033-91034`, `91213-91214`).

Status: **confirmed as persistent per-cylinder knock adaptation**. The OEM unit, signed interpretation, and whether an increasing stored value represents greater learned retard or a differently oriented correction remain inferred. This result does not identify the neighboring six 153-byte tables; those should remain generically named.

## Change and dirty tracking

There is no confirmed global “learned image dirty” flag and no per-page incremental-save scheme.

- Adaptation producers update the RAM mirror directly.
- DTC code maintains record validity, occurrence, and change semantics in the retained structures, but these are domain data rather than proof of a flash-dirty optimization.
- `DAT_40003458` accumulates startup validation/reset reasons, not dirtiness.
- `DAT_40003456` indicates incomplete default reconstruction and suppresses saving.
- `DAT_40009209` is a shutdown-state one-shot indicating that the learned image has been saved during the current power-down sequence (`emira.c:76710-76716`).
- `DAT_40003914` is a dirty/update bitmap for the **separate coding block** and must not be attributed to learned data (`emira.c:75470`, `75486-75495`, `76719-76720`).

The observed policy is therefore full-image persistence at selected lifecycle points, not “write only if a learned byte changed.” Whether an unrecognized domain-local flag also influences some save request remains unknown, but no such flag is required by the confirmed shutdown path.

## Save triggers and shutdown sequencing

### Normal ignition-off save

`FUN_00a742fc()` is a power/ignition state machine. Entry into state 5 is delayed until the operating state permits final shutdown and default reconstruction is no longer active (`emira.c:76647-76651`, `76702-76705`). In state 5 it:

1. checks the one-shot `DAT_40009209`;
2. finalizes/queries diagnostic state, including monitor/DTC `0x8d`;
3. saves if the calibrated operating threshold is met or that diagnostic condition is active;
4. sets the one-shot after a save;
5. separately invokes the coding writer if `DAT_40003914` is nonzero (`emira.c:76708-76722`).

The exact physical meaning of `DAT_400033ae` and the threshold at `CALBASE+0x43ba` is not named here. **Confirmed:** it gates the learned-image write; **inferred:** it is a temperature or other flash-safe shutdown condition.

### Other explicit lifecycle saves

Learned-data saves also occur in reset/session/shutdown handlers when reconstruction is not active (`emira.c:65046-65047`, `65075-65076`, `92633-92634`, `103992-103993`). These paths demonstrate that persistence is tied to orderly lifecycle transitions, not only the main ignition-off state machine.

## Image finalization and flash write behavior

`FUN_00a11f0c()` finalizes the RAM image before programming:

1. writes core length `0x1668` at `0x400189c0`;
2. computes/stores the core CRC at `0x400189c4`;
3. calls `FUN_00a23250()` to finalize additional retained metadata;
4. writes total length `0x3f94` at `0x4001b2e8`;
5. computes/stores the full CRC at `0x4001b2ec`;
6. clears `DAT_4000370e`, disables external interrupts, and calls the writer (`emira.c:30941-30951`).

`FUN_00a11b0c()` then:

- rounds the bulk length down to an 8-byte boundary;
- services watchdog/runtime hooks before destructive and long operations;
- initializes/erases flash at `0x00010000`;
- programs the aligned bulk;
- copies any remainder into an 8-byte temporary phrase and pads unused bytes with `0xff`;
- programs that final phrase;
- tears down/restores the flash driver and performs a watchdog-serviced delay (`emira.c:30678-30716`).

For `0x3f94` bytes, the aligned bulk is `0x3f90` and the final four image bytes share an 8-byte flash phrase whose remaining four bytes are `0xff`.

### Critical section and failure behavior

The high-level save function disables external interrupts immediately before the erase/program operation (`emira.c:30949-30951`). The ignition-off caller re-enables them after the save returns (`emira.c:76713-76717`). Some reset paths proceed directly toward reset/power loss, so re-enable behavior is caller/lifecycle dependent.

The low-level program helper returns status internally, but `FUN_00a11b0c()` ignores the return from `FUN_00a11854()` (`emira.c:30695`, `30711`). No read-back verification, retry, redundant A/B slot, sequence counter, or rollback image is visible in this path. Therefore:

- erase occurs before the only copy is reprogrammed;
- interruption can leave a partially erased/programmed image;
- a silent flash-program failure is not propagated to the caller here;
- the next boot detects damage through size/CRC/identity checks and repairs or reconstructs the affected domains.

This is a simple erase-and-replace persistence strategy whose resilience is based on validation and safe defaults rather than atomic storage.

## Coding/configuration store is separate

The coding store has a different RAM image, flash sector, CRC, dirty bitmap, and writer:

| Property | Learned/runtime store | Coding/configuration store |
|---|---|---|
| Flash base | `0x00010000` | `0x00030000` |
| RAM base | `0x4001735c` | `0x40009120` |
| Image size | `0x3f94` | 160 bytes |
| CRC coverage | nested `0x1668` and `0x3f90` | first `0x9c` bytes |
| Update tracking | no global dirty bit confirmed | `DAT_40003914` |
| Writer | `FUN_00a11f0c` / `FUN_00a11b0c` | `eeprom_write_unknown1` / `flash_write_helper_0x30000` |

`eeprom_write_unknown1()` checks stopped-engine/operating conditions, disables interrupts, prepares flash `0x30000`, calculates the coding CRC, writes 160 bytes, re-enables interrupts, and clears `DAT_40003914` (`emira.c:75480-75495`). The normal shutdown state happens to service both stores in sequence (`emira.c:76713-76721`), but that shared trigger does not make them one image or one integrity domain.

## Open questions for follow-on work

1. Resolve the six 153-byte tables and their runtime copies through ignition and fuel consumers; they are separate from the confirmed six-value knock-learning domain.
2. Trace all writers of `0x40017a00`, `0x40017a02`, `0x40017e10`, and `0x40017e12` to establish idle-adaptation units and learning conditions.
3. Determine whether any retained VVT offset exists or whether this calibration uses only nonpersistent VVT control adaptation.
4. Decode `FUN_00a23250()` metadata finalization and the complete meaning of trailing fields `0x4001b0fc..0x4001b2e7`.
5. Recover flash-driver return semantics to distinguish erase/program/lock failures, even though the high-level learned writer currently discards them.

## Bottom line

The Emira ECU treats learned state as one broad retained RAM image with two nested trust domains. It deliberately preserves compatible adaptive data when only later diagnostic/history content or a calibration-dependent subdomain is invalid, but falls back to a staged, safe default rebuild for core/program incompatibility. Fuel/air grids, ETB endpoint learning, idle-related offsets, six per-cylinder knock values, and DTC history/snapshots are demonstrably persistent. A VVT-specific learned field remains unresolved. Persistence is an orderly-shutdown whole-image erase/program operation with CRC-based next-boot recovery, not an atomic or journaled store.
