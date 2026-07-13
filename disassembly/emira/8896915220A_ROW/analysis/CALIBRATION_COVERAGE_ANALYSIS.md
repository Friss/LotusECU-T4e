# Emira G6 Calibration Coverage Analysis

Target: 2022 Lotus Emira V6 ROW firmware `8896915220A_ROW`, MPC5777C.

## Executive summary

The application has a complete 64 KiB calibration segment. The primary Ghidra export alone is
sparsely named, but Donour's `8900689277A` RomRaider definition supplies a substantial same-family
address map and the matching-ID second code export now independently confirms many of its table
identities. At startup `copy_calrom_to_calbase()` copies flash
`0x00020000..0x0002ffff` to RAM `0x4002e000..0x4003dfff` and sets `CALBASE_addr` to the RAM copy
(`emira.c:24822-24828`). `select_calbase_addr()` can instead point consumers directly at flash
(`emira.c:24832-24842`). Nearly all consumers use `CALBASE_addr + offset`, which makes the offset
stable regardless of the selected backing store.

Only eight functional calibration objects currently have useful `CAL_` names: two 20x20 AFR
tables and their known axes, plus one rev-limit table and its axes/hysteresis data. This is enough to
prove the calibration access model and begin a definition, but not enough to expose the firmware
safely as a tune.

The companion `CALIBRATION_LOOKUP_INVENTORY.md` and generated
`calibration_lookup_inventory.csv` extend this result structurally: 333 direct CALBASE-relative
lookup calls expose 628 unique literal offsets across data, axes, and vectors. The gap is therefore
semantic naming and byte validation, not an absence of recoverable table structure.

`ROMRAIDER_DEFINITION_VALIDATION.md` cross-checks the `8900689277A` XML against that inventory. Of 96
top-level objects, 58 have at least one direct code-address match and 54 have every listed data/axis
address directly matched. The second export makes the XML an exact code/definition pair for
`8900689277A` and supplies cross-version names for the ROW analysis. It is still not byte-level
validation: the primary target is `8896915220A_ROW`, and neither target's canonical calibration bytes
are present.

## Calibration storage and validation

| Item | Evidence | Confidence |
|---|---|---|
| Flash calibration base | `memmove(..., 0x20000, 0x10000)` in `copy_calrom_to_calbase()` (`emira.c:24822-24828`) | Confirmed |
| RAM working base | `CAL_base`/`CALBASE_addr`; `FUN_00a05984()` explicitly compares the active pointer with `0x4002e000` (`emira.c:24845-24851`) | Confirmed |
| Size | Copy length `0x10000` (65,536 bytes) | Confirmed |
| Runtime flash/RAM selection | `select_calbase_addr(bool)` chooses `0x20000` or `CAL_base` (`emira.c:24832-24842`) | Confirmed |
| Integrity field | CRC16 is calculated from calibration offset `0x20` for a runtime-derived length, then compared with the word at offset `0xfffe` (`emira.c:23631`, `emira.c:28557`) | Confirmed mechanism; coverage length needs tracing |
| Structural sanity check | Bytes at offsets `0xe2`, `0x218`, `0x290`, and `0x337` are checked for fixed values (`emira.c:23633-23634`) | Confirmed; semantic purpose unknown |

The working-copy design is a major architectural change from the Evora T6 analysis. Any definition,
patch, or live-tuning tool must distinguish the persistent flash address (`0x0002xxxx`) from the
runtime RAM address (`0x4002exxx` onward). The portable identity of a calibration item is its 16-bit
offset within the block.

## Named coverage

The current export contains nine unique `CAL_` tokens, one of which is the base symbol. The eight
functional names are:

| Name | Shape/type | Current interpretation |
|---|---|---|
| `CAL_inj_afr1` | `u8_afr_1/20+5[400]` | 20x20 AFR target table |
| `CAL_inj_afr1_X_rpm` | `u8_rspeed_125/4+500rpm[20]` | AFR1 engine-speed axis |
| `CAL_inj_afr1_Y_load` | `u8_load_1173mg/255stroke[20]` | AFR1 load axis |
| `CAL_inj_afr2` | `u8_afr_1/20+5[400]` | second 20x20 AFR table |
| `CAL_revlimit_table1` | `u16_rspeed_rpm[16]` | rev-limit surface/data |
| `CAL_revlimit_table1_X_coolant_temp` | `u16_temp_5/8-40c[4]` | four-point coolant axis |
| `CAL_revlimit_table1_Y_time` | `uint16_t[4]` | four-point time axis |
| `CAL_revlimit_hysteresis_offset` | `uint8_t[7]` | hysteresis/offset selection data |

The rev-limit objects are consumed together by interpolated lookups at `emira.c:41221-41274`.
`CAL_inj_afr1` and its two axes are consumed by a 20x20 interpolated lookup at
`emira.c:49988-49989`. `CAL_inj_afr2` is referenced through `CALBASE_addr` in the fuel-control path
at `emira.c:31570` and `emira.c:31633`.

These type names appear inherited from the Evora analysis and are plausible because the same lookup
idioms and encodings recur. They are still analyst annotations, not independent proof of the stock
bytes or the exact operating-mode meaning of “AFR1” versus “AFR2.”

## Strong unnamed table evidence

Many tables are structurally identifiable even though their semantics are not yet named. Examples:

- Offset `0x19ae` is an 8x8 table with axes at `0x199e` and `0x19a6`
  (`emira.c:29105-29106`).
- Offset `0x52c8` is an 8x8 table with axes at `0x52b8` and `0x52c0`
  (`emira.c:29171-29172`).
- Offsets `0xed0a`, `0xed5a`, `0xedaa`, and `0xedfa` are a family of 8x8 tables with adjacent
  eight-byte axes (`emira.c:32235-32270`).
- Offset `0x7044` is an 8x8 table with axes at `0x7034` and `0x703c`; similar families appear at
  `0x6ff4`, `0x7094`, and `0x71c4` (`emira.c:32660-32672`).
- Offset `0x1836` is an 8x8 table with adjacent axes at `0x183e` and `0x1846`
  (`emira.c:76657-76658` in the engine shutdown/diagnostic state path).

Those calls establish dimensions and relative layout, but not units or user-facing names. A table
must not be promoted to a tuning definition until its inputs, output scaling, mode arbitration, and
stock bytes have all been verified.

## Coverage compared with the Evora reference

| Area | Evora GT430 analysis | Current Emira export |
|---|---|---|
| Storage/base | Directly annotated flash-oriented `CAL_*` map | 64 KiB flash block with switchable RAM working copy |
| Fuel targets | Extensively named | Two 20x20 AFR tables partly named |
| Rev limiting | Named families | One 4x4/16-value family named |
| Ignition and knock | Broad named coverage | Logic is present; calibration offsets mostly anonymous |
| Torque/throttle | Broad named coverage | Logic is present; calibration offsets mostly anonymous |
| VVT/idle/thermal | Broad named coverage | Numerous lookup families, almost no calibration names |
| Diagnostics | Named thresholds/masks | Many offsets visible, semantic names largely absent |
| Definition artifact | XML/CPT artifacts exist | Matching-ID `8900689277A` XML and code export are present and structurally reconciled; exact `8896915220A_ROW` compatibility is unproved |

See `CALIBRATION_LOOKUP_INVENTORY.md` for the call-level population, dimensions, reuse, domain
density, extractor, and machine-readable CSV. See `ROMRAIDER_DEFINITION_VALIDATION.md`,
`8900689277A_RECONCILIATION.md`, and `emira_romraider_definition_crosswalk.csv` for the XML-to-code
and cross-firmware audits. Direct address matches validate structure, not exact target bytes,
engineering scaling, or safe-edit status.

## Required work before tuning use

1. Recover the exact 64 KiB stock calibration bytes and record a hash tied to
   `8896915220A_ROW`.
2. Export calibration symbols with absolute address, block-relative offset, type, size, and source
   evidence. Offset must be the primary cross-reference key.
3. Trace lookup consumers subsystem by subsystem, beginning with ignition, torque/throttle, fuel,
   knock, VVT, and protection limits.
4. Validate axis monotonicity, table dimensions, encodings, and stock numeric ranges directly from
   the bytes.
5. Resolve the CRC coverage length and stored CRC format before attempting any persistent edit.
6. Treat the bootloader RSA/SHA-1 signature described in `BOOTLOADER_ANALYSIS.md` as a separate
   packaging/programming constraint; the application CRC is not a substitute for that signature.

## Limitations

- `emira.c` is a decompiler export, not source code. Array declarations and symbol boundaries can be
  wrong where Ghidra has overlapping labels.
- The original Intel HEX named by the bootloader note is not present in this directory, so this pass
  cannot independently verify calibration bytes, addresses, or checksums against a raw image.
- A code reference establishes that an offset is used, not what a calibrator intended it to mean.
- No claim in this note authorizes flashing or establishes that a modified image will pass the
  bootloader's signature checks.
