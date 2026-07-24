# Emira G6 Application CAN Topology Analysis

## Scope and principal conclusions

This report covers only the main application at `0x00A00000+`. The bootloader CAN implementation at
`0xFFFC0000`, including the proven `0x730 -> 0x630` programming pair and accepted `0x7FF` request,
is deliberately excluded; it is documented in `BOOTLOADER_PROGRAMMING_ANALYSIS.md`.
The application reinitializes that same `0xFFFC0000` peripheral for its own descriptor-driven
traffic after handoff; the two protocol/state machines are separate program contexts, not separate
claims about the physical register block.

The application uses three FlexCAN register blocks:

| Controller base | Initialization | Service model | Best-supported role |
|---|---|---|---|
| `0xFFFC0000` | `FUN_00a059ac` -> `FUN_00a5096c` | table-driven RX/TX | vehicle network; decoded state flows into wheel/vehicle-state and powertrain logic |
| `0xFFFC4000` | `FUN_00a47754` -> `FUN_00a4e66c` | table-driven RX/TX | second vehicle network; decoded state flows into gearbox/body/vehicle-mode logic |
| `0xC3E60000` | `FUN_00a06148` -> `flexcan_init` | table-driven RX/TX plus fixed mailboxes | mixed application network and proprietary engineering/debug transport |

All three bases and their application initializers are **confirmed** (`emira.c:24854-24860`,
`25144-25150`, `60040-60051`, `65209-65305`, `66747-66888`, `67170-67259`). Calling the first two
“vehicle buses” is a strong inference from their downstream decoded state, not a recovered harness or
connector assignment.

The most important corrections to the earlier CAN overview are:

- `0x202` is not established as a vehicle signal frame. It is the eight-byte request side of a
  proprietary command channel whose replies use `0x200` and `0x201`.
- `0x40..0x47` are not plausible gearbox, ESP, or body frames. They are an ECU-unlock-gated
  engineering protocol with arbitrary memory reads, writes, and block transfers.
- The normal vehicle-message IDs live in preinitialized RAM descriptor arrays. The C export retained
  the driver and array addresses but not their initial contents, and this directory has no matching
  application binary. Consequently, publishing exact gearbox/ESP/body arbitration IDs from this
  export would be invention.

## Common table-driven architecture

Each controller is configured for 64 message buffers, clears their control/data fields, installs
per-mailbox receive IDs, enables interrupt bits, and leaves freeze mode. The timing word is
`0x017A0007` on all three paths (`emira.c:65209-65305`, `66747-66888`, `67170-67259`). The exact CAN
bit rate is **unknown** because this word alone does not prove the peripheral source clock.

The normal application network is described by 28-byte RX and TX records. The three sets are:

| Base | RX descriptor base/count | TX descriptor base/count | Driver anchors |
|---|---|---|---|
| `0xFFFC0000` | `0x400024EC` / `DAT_40003304` | `0x40002D88` / `DAT_4000330A` | `emira.c:67221-67248`, `67272-67331`, `67379-67469` |
| `0xFFFC4000` | `0x40001F3C` / `DAT_40003300` | `0x400028C0` / `DAT_40003306` | `emira.c:65260-65287`, `65316-65373`, `65405-65506` |
| `0xC3E60000` | `0x40002498` / `DAT_40003302` | `0x40002C78` / `DAT_40003308` | `emira.c:66831-66858`, `66907-66956`, `66995-67080` |

### Receive contract

The initializer reads an 11-bit ID and a mailbox index from every RX record, assigns hardware
mailbox `index + 8`, and enables its interrupt (`emira.c:65260-65279`, `66831-66851`,
`67221-67241`). On service, the driver snapshots the DLC and eight data bytes, marks the record new,
and calls its decode callback (`emira.c:66995-67080`, `67379-67469`).

Each RX record also has a threshold, age, new-data flag, and callback. With no new frame, the age is
advanced and the callback is invoked when the threshold is crossed; a calibration byte can suppress
this timeout callback. On receipt, age is reset and the same record callback plus an optional
subscriber callback is invoked. This is direct evidence for per-message validity/timeout handling,
although individual timeout values are unavailable without the descriptor contents
(`emira.c:65443-65506`, `67054-67080`, `67443-67469`).

### Transmit contract and cadence

Every TX record contains a mailbox index, period, enable/one-shot state, DLC, 11-bit ID, payload
builder callback, and optional post-send callback. When due, the driver sets TX inactive, writes the
record ID, calls the payload builder, writes DLC, and activates the mailbox with code `0xC`
(`emira.c:65316-65373`, `66907-66956`, `67272-67331`). Thus direction and length are structurally
known for every record, but their values are not recoverable from this C export.

Relative cadence is provable:

- `0xFFFC0000` TX/RX accounting advances in units of 1 (`emira.c:67290-67308`,
  `67379-67469`) and its TX service is in the most frequent application wrapper
  (`FUN_00a46058`, `emira.c:58776-58816`).
- `0xFFFC4000` and `0xC3E60000` accounting advances in units of 5
  (`emira.c:65334-65348`, `65443-65506`, `66921-66935`, `67054-67080`) and their services occur in
  less frequent wrappers `FUN_00a46408` and `FUN_00a4648c` (`emira.c:58973-59030`).

This is consistent with 1 ms and 5 ms servicing respectively, but the absolute scheduler tick is not
fully proved by the decompiler export. Descriptor periods and therefore individual message rates
remain **unknown**.

## Fixed `0xC3E60000` mailbox inventory

The fixed mailbox layout is recoverable independently of the anonymous tables:

| MB / address | Direction | 11-bit ID | DLC | Behavior | Confidence |
|---|---|---:|---:|---|---|
| MB8 / `0xC3E60100` | TX | `0x7A0` | 1..8, command-dependent | fixed reply mailbox for engineering memory reads/block reads | confirmed |
| MB9 / `0xC3E60110` | TX | `(request byte 0 << 3) + 1` | 2..8, command-dependent | caller-selected reply ID for memory-read operations | confirmed arithmetic; tester meaning unknown |
| MB12 / `0xC3E60140` | TX | `0x200` | 8 | proprietary command response stream | confirmed |
| MB13 / `0xC3E60150` | TX | `0x201` | 8 | proprietary command response stream | confirmed |
| MB14 / `0xC3E60160` | RX | `0x202` | accepted only at 8 | proprietary command requests | confirmed |
| MB15 / `0xC3E60170` | RX | `0x40..0x47` | command-dependent | unlock-gated raw-memory/debug requests and transfer data | confirmed |

The `0x200` and `0x201` transmit helpers explicitly write those arbitration values and always copy
eight bytes (`emira.c:24175-24200`, `24214-24239`). The `0x202` handler checks the ID, copies all
eight bytes, and passes the record to `FUN_00a058dc` (`emira.c:25429-25475`). That function admits
only DLC 8 into a two-entry queue (`emira.c:24805-24818`). `FUN_00a04c1c` consumes that queue as a
stateful command interpreter with pointer-based memory copy, calibration-bank selection, and logger
configuration operations (`emira.c:24240-24793`). Therefore the triplet is classified as
**internal/service tooling**, not vehicle dynamics.

The initialization values also independently show MB12=`0x200`, MB13=`0x201`, MB14=`0x202`, and
MB15 base ID=`0x40` (`flexcan_init`, `emira.c:66791-66822`). Masking/filter behavior permits the
low-ID family on MB15; software then explicitly dispatches only `0x40..0x47`.

## `0x40..0x47`: engineering/debug protocol

The handler first obtains `ecu_unlocked` through `get_ecu_locked_state`. It dispatches commands only
when that boolean is true (`emira.c:25482-25517`). Initialization likewise enables the MB15
interrupt only in this state (`emira.c:66823-66830`). The misleading recovered local name
`locked_state` must not reverse this conclusion: the cal-magic check sets `ecu_unlocked`, and the
getter returns that value (`emira.c:23631-23637`, `23831-23842`).

| ID | Accepted DLC(s) | Proven operation |
|---:|---:|---|
| `0x40` | 4 | interpret bytes 0..3 as an address, read 32 bits, reply through MB8/`0x7A0` (`emira.c:25518-25531`) |
| `0x41` | 4 or 6 | 16-bit address read, or 1/2/4-byte read from an address in bytes 1..4 with caller-selected reply ID (`emira.c:25540-25601`) |
| `0x42` | 4 or 7 | 8-bit address read, or copy up to eight bytes from an arbitrary address into a caller-selected reply (`emira.c:25608-25657`) |
| `0x43` | 1, 4, 5, or 6 | fixed pointer/version response and arbitrary-address block-read setup/continuation through MB8 (`emira.c:25664-25798`) |
| `0x44` | 8 | write 32-bit bytes 4..7 to address in bytes 0..3 (`emira.c:25801-25819`) |
| `0x45` | 6 | write 16-bit bytes 4..5 to address in bytes 0..3 (`emira.c:25820-25838`) |
| `0x46` | 5 | write byte 4 to address in bytes 0..3 (`emira.c:25839-25856`) |
| `0x47` | 5 to start, then variable | write a requested byte count from following CAN payloads to an arbitrary destination address (`emira.c:25857-25903`) |

This family is conclusively **internal/debug**, not merely “unknown application CAN.” It is also a
high-impact interface: when the calibration magic enables it, external frames can dereference and
modify arbitrary addresses. The unlock check is a real gate, but no per-command address allowlist is
visible.

## Vehicle, gearbox, ESP, body, and diagnostic dataflow

### Confirmed structural dataflow

The table-driven buses decode RX records into large shared state blocks at `0x4001Bxxx` and
`0x4001Cxxx`. The downstream application applies explicit validity states before consuming them.
For example, `FUN_00a059dc`, immediately after servicing `0xFFFC0000`, selects between parallel
decoded sources, scales four related 16-bit values, and produces validity bytes plus shared speed-like
values (`emira.c:24875-25025`). `FUN_00a06194`, after servicing `0xC3E60000`, copies decoded status,
mode, pressure/load-like, and torque-like fields into application state with validity fallbacks
(`emira.c:25166-25293`). `FUN_00a47784`, after servicing `0xFFFC4000`, consumes enumerated mode/status
fields and two signed scaled values (`emira.c:60065-60130`).

These paths confirm that application CAN feeds vehicle/powertrain control state and that invalid
messages are replaced by explicit defaults. They do **not** prove the external ECU that originated
each anonymous descriptor.

### Domain attribution

| Domain | What is established | What remains unknown |
|---|---|---|
| Vehicle speed / ESP | four related speed-like inputs, parallel-source selection, validity states, and downstream use are visible on the `0xFFFC0000` path | arbitration IDs, byte/bit positions, wheel order, scaling units, and whether both sources are separate buses or redundant providers |
| Gearbox / transmission | decoded gear/mode/status and signed request-like values are consumed after `0xFFFC4000` and `0xC3E60000` service | source controller, IDs, torque-request encoding, shift phases, alive counters, and checksums |
| Body / vehicle mode | numerous Boolean and enumerated decoded fields feed common mode/status flags | originating body controller, IDs, and signal contracts |
| ESP / torque intervention | the broader application has ESP/throttle-intervention vocabulary and CAN-fed state, but no recovered descriptor ties a specific ID to intervention demand | ID, scaling, timeout reaction, and authority ordering |
| Application diagnostics | `0x200/0x201/0x202` and `0x40..0x47` are proprietary engineering channels; a larger diagnostic manager exists elsewhere | whether normal UDS/OBD uses a table descriptor on one of these buses, and its application request/response IDs |

No payload signal is decoded in this report unless arithmetic and provenance establish it. In
particular, the speed-like and torque-like fields above are intentionally not assigned units or
named as specific wheel/gearbox/ESP signals.

## What is still needed for a complete DBC-grade map

1. Recover the original application image or Ghidra project so the initial values of the six
   descriptor arrays can be exported: ID, mailbox, DLC, period/timeout, and callback pointer.
2. Name each callback from its writes into `0x4001Bxxx/0x4001Cxxx`, then trace those variables to
   torque arbitration, gearbox, ESP, body, and diagnostic consumers.
3. Obtain captures from all three physical buses and correlate callback validity transitions with
   unplugged modules, wheel-speed stimulation, shifts, and drive-mode changes.
4. Verify the peripheral clock before converting `0x017A0007` into a claimed baud rate.
5. Treat `0x40..0x47` and `0x200..0x202` as sensitive service interfaces during bench work; do not
   probe arbitrary payloads on an unlocked ECU.

## Confidence summary

- **Confirmed:** three application FlexCAN bases; common 64-message-buffer table architecture; per-message RX
  timeout/validity machinery; callback-built TX frames; fixed IDs `0x200`, `0x201`, `0x202`,
  `0x40..0x47`, and fixed memory-read reply `0x7A0`; all fixed-frame directions and DLC constraints.
- **Confirmed:** `0x200..0x202` form a proprietary command channel and `0x40..0x47` form an
  unlock-gated arbitrary-memory engineering protocol.
- **Inferred:** `0xFFFC0000` and `0xFFFC4000` are the principal vehicle-facing networks and the
  table-driven callbacks cover gearbox/ESP/body traffic.
- **Unknown:** the normal vehicle-message IDs, individual TX rates, per-ID timeout values, bus names,
  physical connector routing, signal bit layouts/scaling, and exact controller-to-module topology.
