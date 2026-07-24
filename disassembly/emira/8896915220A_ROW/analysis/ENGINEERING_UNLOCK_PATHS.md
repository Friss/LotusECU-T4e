# Emira Engineering Unlock Paths

## Scope and defensive handling

This report traces the application’s engineering/debug enable from calibration flash through startup, mailbox initialization, and the two fixed proprietary CAN families. It also compares that mechanism with ordinary application OBD/DTC handling and bootloader UDS programming.

The fixed services include arbitrary-address memory operations. Their capabilities and trust boundaries are documented for defensive review, but this note intentionally omits complete request payloads, byte-order recipes, and step-by-step procedures that would turn the analysis into an exploitation guide.

## Executive findings

1. `ecu_unlocked` is a **boot-derived application boolean**, not a UDS security-session flag. Startup copies the 64 KiB calibration from flash `0x00020000` to RAM `0x4002e000`, calculates a CRC, clears the separate `BOOL_40003331`, then compares four RAM-calibration bytes with fixed constants. Only a four-way match sets `ecu_unlocked=true` ([emira.c:23610](../emira.c#L23610), [emira.c:23613](../emira.c#L23613), [emira.c:23631](../emira.c#L23631), [emira.c:23632](../emira.c#L23632), [emira.c:23633](../emira.c#L23633), [emira.c:23635](../emira.c#L23635)).
2. The four prerequisites are calibration offsets `0x00e2`, `0x0218`, `0x0290`, and `0x0337`, expected to contain `0x0d`, `0xb8`, `0x45`, and `0xd4` respectively. Signed decompiler literals `-72` and `-44` are byte values `0xb8` and `0xd4`, not negative semantic quantities ([emira.c:23633](../emira.c#L23633)–[23635](../emira.c#L23635)).
3. `get_ecu_locked_state` is misnamed: it returns status code zero on a valid pointer and stores the value of `ecu_unlocked` into the caller’s output. `true` means engineering access enabled ([emira.c:23793](../emira.c#L23793)–[23803](../emira.c#L23803)).
4. The `0x40..0x47` mailbox family is gated twice: FlexCAN initialization enables MB15’s interrupt only when the startup boolean is true, and the receive handler rechecks the boolean before dispatch ([emira.c:66747](../emira.c#L66747), [emira.c:66761](../emira.c#L66761), [emira.c:66823](../emira.c#L66823), [emira.c:25482](../emira.c#L25482), [emira.c:25503](../emira.c#L25503), [emira.c:25515](../emira.c#L25515)). When enabled, the family provides raw reads, single-value writes, and a streamed arbitrary-address write with no visible address allowlist.
5. `0x200/0x201/0x202` is a separate, always-initialized proprietary calibration/logger command channel. Requests arrive on `0x202`; responses use `0x200` and `0x201`. Its state machine can select the RAM or flash calibration view, set pointers, read/write through those pointers, configure logging records, and copy calibration flash into the RAM shadow. No `ecu_unlocked` check is visible in its queue or interpreter ([emira.c:24174](../emira.c#L24174), [emira.c:24214](../emira.c#L24214), [emira.c:24417](../emira.c#L24417), [emira.c:24804](../emira.c#L24804), [emira.c:25429](../emira.c#L25429)).
6. Live changes to the RAM calibration shadow do **not** cause the four-byte predicate to be reevaluated. They also do not, by themselves, persist to calibration flash. The application contains no general calibration-flash writer; programming the `0x00020000` calibration region belongs to the bootloader’s descriptor-gated, programming-session, SecurityAccess path.
7. The recovered normal application OBD/DTC services do not write `ecu_unlocked` and do not invoke its initializer. Bootloader UDS can ultimately install a calibration containing the magic pattern, but only through the programming/reflash lifecycle; the application then derives the engineering state on the next boot. Thus **ordinary OBD/UDS does not expose a direct runtime “enable engineering mode” service** in the recovered code.
8. The un-gated proprietary `0x202` channel materially weakens the overall boundary because it contains generic live-memory operations. It is not normal OBD/UDS, and a RAM write is not persistent. Also, merely changing `ecu_unlocked` after CAN initialization would not retroactively perform the MB15 interrupt-enable step. Nevertheless, this channel should be treated as security-sensitive and filtered from untrusted networks.

## 1. Startup lifecycle

### 1.1 Calibration copy precedes the decision

`main` calls `init_core_system()` before `setup_post_init()` ([emira.c:22554](../emira.c#L22554)–[22555](../emira.c#L22555)). `init_core_system` begins with `copy_calrom_to_calbase`, which copies `0x10000` bytes from calibration flash `0x00020000` to RAM `CAL_base` and points `CALBASE_addr` at that RAM shadow ([emira.c:23610](../emira.c#L23610)–[23614](../emira.c#L23614), [emira.c:24823](../emira.c#L24823)–[24828](../emira.c#L24828)).

The unlock predicate therefore reads the **fresh RAM copy of persistent calibration flash**, not an unrelated coding block or learned-data image. The CRC over calibration offsets `0x20` through the penultimate stored bytes is computed immediately before the magic check ([emira.c:23631](../emira.c#L23631)).

### 1.2 Four-byte predicate

The exact decision is an AND of four comparisons:

| Calibration offset | Expected byte | Decompiler representation |
|---:|---:|---|
| `0x00e2` | `0x0d` | `0xd` |
| `0x0218` | `0xb8` | `-72` as signed `char` |
| `0x0290` | `0x45` | `0x45` |
| `0x0337` | `0xd4` | `-44` as signed `char` |

All four must match before `ecu_unlocked` is set true ([emira.c:23633](../emira.c#L23633)–[23635](../emira.c#L23635)). There is no partial level or counter. This looks like a deliberately dispersed development-build/calibration signature rather than a user-entered challenge-response.

The initialized global is false on a fresh reset; the visible application code has only one named assignment to true and no ordinary setter. A reset reconstructs normal RAM state, recopies calibration flash, and repeats the predicate.

### 1.3 The similarly named boolean is different

`BOOL_40003331` is cleared on every initialization immediately before the predicate, but it is not the value returned by `get_ecu_locked_state` ([emira.c:23632](../emira.c#L23632), [emira.c:23800](../emira.c#L23800)). Elsewhere, `BOOL_40003331` bypasses calibration-integrity or coding restrictions ([emira.c:22586](../emira.c#L22586), [emira.c:28555](../emira.c#L28555), [emira.c:50752](../emira.c#L50752), [emira.c:75593](../emira.c#L75593)). It should not be conflated with `ecu_unlocked` merely because both are development-related booleans.

### 1.4 RAM-calibration mismatch monitoring

The main loop continuously compares one byte of the active RAM calibration with its flash source. On any mismatch it records both addresses and byte values and sets a calibration-integrity fault unless the separate `BOOL_40003331` bypass is active ([emira.c:22566](../emira.c#L22566)–[22588](../emira.c#L22588)).

This does not relock the engineering state and does not rerun the four-byte predicate. It is a detection/latching path, not a state-transition path for `ecu_unlocked`.

## 2. `get_ecu_locked_state` semantics

The function name and recovered local variable names are misleading:

```text
get_ecu_locked_state(output_pointer):
    if pointer is null: return error 5
    *pointer = ecu_unlocked
    return success 0
```

([emira.c:23793](../emira.c#L23793)–[23803](../emira.c#L23803)). Callers correctly treat output `true` as permission:

- the raw engineering receive handler dispatches only when it is true ([emira.c:25503](../emira.c#L25503)–[25517](../emira.c#L25517));
- `flexcan_init` sets the MB15 interrupt mask only when it is true ([emira.c:66761](../emira.c#L66761), [emira.c:66823](../emira.c#L66823)–[66828](../emira.c#L66828)).

The function’s integer return value reports whether the output argument was valid; it is not the lock state. Any review that tests `_locked_state_` rather than `local_10[0]` will reverse or obscure the security conclusion.

## 3. Fixed mailbox initialization

The `0xC3E60000` FlexCAN controller reserves four fixed message buffers:

| Mailbox | Direction | Fixed ID/base | Role |
|---|---|---:|---|
| MB12 | TX | `0x200` | proprietary command responses/stream |
| MB13 | TX | `0x201` | proprietary command responses/stream |
| MB14 | RX | `0x202` | proprietary command requests |
| MB15 | RX | base `0x40` with mask | raw engineering/debug family `0x40..0x47` |

The message-buffer register setup is visible at [emira.c:66796](../emira.c#L66796)–[66815](../emira.c#L66815). Interrupt masks for MB12–14 are enabled unconditionally; MB15’s bit is conditional on `ecu_unlocked` ([emira.c:66816](../emira.c#L66816)–[66829](../emira.c#L66829)).

This creates two independent service planes on the same controller:

```text
always initialized:  RX 0x202 -> command/calibration/logger interpreter -> TX 0x200/0x201
boot-unlock gated:   RX 0x40..0x47 -> direct engineering memory handler -> reply mailbox(es)
```

## 4. Unlock-gated `0x40..0x47` engineering protocol

### 4.1 Gate behavior

The handler reads `ecu_unlocked` on every invocation and acknowledges/discards frames without dispatch when false ([emira.c:25482](../emira.c#L25482)–[25516](../emira.c#L25516)). In the normal locked boot, MB15’s interrupt was also left disabled, so ordinary arrival of a matching frame should not schedule this handler.

### 4.2 Capability map

The family implements these proven operation classes:

| ID | Capability | Address restriction found? | Response behavior |
|---:|---|---|---|
| `0x40` | read one 32-bit value from caller-supplied address | none visible | fixed engineering reply mailbox, ID `0x7a0` |
| `0x41` | read 16-bit value, or selected-width value from caller-supplied address | none visible | fixed or caller-selected reply ID |
| `0x42` | read byte, or copy up to eight bytes from caller-supplied address | none visible | fixed or caller-selected reply ID |
| `0x43` | version/pointer response and arbitrary-address block-read setup/continuation | none visible | segmented through fixed reply mailbox |
| `0x44` | write one 32-bit value to caller-supplied address | none visible | no data reply established |
| `0x45` | write one 16-bit value to caller-supplied address | none visible | no data reply established |
| `0x46` | write one byte to caller-supplied address | none visible | no data reply established |
| `0x47` | initialize and stream a caller-selected byte count to caller-supplied address | none visible | stateful transfer |

Direct evidence includes the 32-bit dereference at [emira.c:25518](../emira.c#L25518)–[25531](../emira.c#L25531), arbitrary copy reads at [emira.c:25624](../emira.c#L25624)–[25656](../emira.c#L25656), block reads at [emira.c:25664](../emira.c#L25664)–[25763](../emira.c#L25763), scalar stores at [emira.c:25801](../emira.c#L25801)–[25849](../emira.c#L25849), and streamed writes at [emira.c:25857](../emira.c#L25857)–[25900](../emira.c#L25900).

The code checks IDs and lengths but contains no address-range allowlist, MPU-domain test, session timeout, seed/key challenge, or per-operation permission tier. Once the boot calibration enables the family, its memory authority is effectively process-wide.

### 4.3 What it does not prove

A raw store to a flash-mapped address is not equivalent to flash programming. The visible handler performs CPU loads/stores and `memmove`; it does not call the flash erase/program driver. Its unambiguous persistent capabilities are therefore not the same as its broad live RAM/peripheral capabilities.

## 5. Always-initialized `0x200/0x201/0x202` channel

### 5.1 Transport

`flexcan_c_rx_202` accepts standard ID `0x202`, copies all eight bytes, and queues the request ([emira.c:25429](../emira.c#L25429)–[25471](../emira.c#L25471)). `FUN_00a058dc` admits only DLC 8 into a two-entry ring ([emira.c:24804](../emira.c#L24804)–[24817](../emira.c#L24817)). Response helpers fill eight-byte frames on fixed `0x200` and `0x201` mailboxes ([emira.c:24174](../emira.c#L24174)–[24196](../emira.c#L24196), [emira.c:24214](../emira.c#L24214)–[24235](../emira.c#L24235)).

Neither the receive path nor the interpreter calls `get_ecu_locked_state`.

### 5.2 Interpreter capabilities

`FUN_00a04eec` is a stateful command interpreter with a session-like enable flag and asynchronous response handling ([emira.c:24414](../emira.c#L24414) onward). Without reproducing request encodings, the recovered cases establish:

- set one of two internal pointer registers;
- read bytes from the selected pointer and advance it;
- write request bytes to the selected pointer and advance it;
- select whether `CALBASE_addr` references calibration flash or the RAM shadow ([emira.c:24608](../emira.c#L24608)–[24622](../emira.c#L24622), [emira.c:24833](../emira.c#L24833)–[24841](../emira.c#L24841));
- configure, enable, disable, and clear up to ten logger/DAQ records ([emira.c:23911](../emira.c#L23911)–[23955](../emira.c#L23955), [emira.c:24483](../emira.c#L24483)–[24545](../emira.c#L24545));
- copy data between selected calibration pointers, including a guarded flash-calibration-to-RAM-shadow operation ([emira.c:24082](../emira.c#L24082)–[24133](../emira.c#L24133), [emira.c:24698](../emira.c#L24698)–[24704](../emira.c#L24704));
- expose software/version data and logger stream data.

The generic helpers are direct `memmove` wrappers: `FUN_00a04a18` copies from the selected pointer into a response buffer, while `FUN_00a04a80` copies request data into the selected pointer ([emira.c:24138](../emira.c#L24138)–[24169](../emira.c#L24169)). `FUN_00a044d0` translates a calibration-flash address into the RAM-shadow window when RAM calibration is selected ([emira.c:23891](../emira.c#L23891)–[23906](../emira.c#L23906)).

### 5.3 Relationship to the unlock gate

This service plane can alter live RAM and RAM calibration without first satisfying `ecu_unlocked`. That means it can, in principle, reach security-relevant application state, including the unlock boolean, because no general pointer allowlist is visible.

However, three limitations matter:

1. The four-byte predicate runs only during `init_core_system`; editing those four RAM-shadow bytes later does not rerun it.
2. MB15 interrupt enable is latched during `flexcan_init`; changing only the boolean after initialization does not repeat the mailbox setup.
3. A live RAM change disappears at reset unless a separate flash-programming path makes it persistent.

Thus the channel is a serious live-memory exposure, but it is not evidence of an ordinary, persistent, single-service unlock. Defensive controls should protect `0x202` even on ECUs whose calibration does not contain the engineering pattern.

## 6. Persistence and calibration reflash

### 6.1 What persists the engineering state

`ecu_unlocked` itself is not stored in coding flash or learned-data flash. The durable prerequisite is the four-byte pattern in the 64 KiB calibration at `0x00020000`. On every reset:

```text
calibration flash 0x00020000
    -> copy to RAM 0x4002e000
    -> evaluate four offsets once
    -> ecu_unlocked boolean
    -> conditional MB15 interrupt enable
```

Normal application persistence writers target learned data at `0x00010000` and coding at `0x00030000`; they do not write the calibration sector. The application calibration/logger protocol demonstrably edits the RAM shadow and can restore it from flash, but this trace finds no reverse RAM-shadow-to-calibration-flash program operation.

### 6.2 Bootloader UDS path

The bootloader has an independent security state and diagnostic transport. Its UDS-style programming flow requires:

- diagnostic programming session state 2;
- successful SecurityAccess;
- a start address present in a 15-entry region descriptor table;
- length within that descriptor;
- asynchronous erase and TransferData sequencing.

The calibration base `0x00020000` is an explicitly supported erase/program region, and calibration is covered by application authenticity hashing. See `BOOTLOADER_PROGRAMMING_ANALYSIS.md`; direct code evidence is summarized at `emira.c:16036–16430` and `18535–18673`.

A legitimately authorized calibration reflash can therefore make the four-byte predicate durable. The application will not observe the resulting engineering state until it restarts and reruns `init_core_system`.

## 7. Can OBD or UDS enable it without calibration reflash?

### Application OBD/DTC framework

No. The recovered application OBD Mode 01 handlers are read-oriented, and the DTC/session framework manages diagnostic status, snapshots, and service state. A complete cross-reference finds no assignment to `ecu_unlocked` except the startup magic check and no call that reruns that check. `get_ecu_locked_state` is read-only.

### Bootloader UDS SecurityAccess

No direct equivalence. Bootloader SecurityAccess sets a bootloader programming-session flag; it does not set application `ecu_unlocked`. The two booleans live in different program contexts and authorize different operations. SecurityAccess can authorize a calibration reflash, which may indirectly change next-boot application state if the installed calibration contains the four-byte pattern.

### Bootloader WriteDataByIdentifier/RoutineControl

No direct enable was found. These services are session- and range-controlled and have no recovered path to the application boolean. RoutineControl’s signature/bootability functions validate images; they do not toggle the application engineering flag.

### Proprietary `0x202` service

This is not ordinary OBD/UDS. It can alter live application memory without the four-byte gate and therefore creates a possible runtime path to security-sensitive state. The analysis does not claim a persistent unlock through it, and it intentionally does not provide an operational procedure.

## 8. Security and safety assessment

### Positive properties

- The raw `0x40..0x47` family is disabled by default unless four dispersed calibration bytes match.
- Permission is checked both at mailbox interrupt setup and at handler entry.
- Persistent calibration programming belongs to a descriptor-bounded, session- and SecurityAccess-gated bootloader path.
- Changing RAM calibration is detected by continuous RAM-versus-flash comparison.
- Reset reconstructs the application boolean from persistent calibration rather than trusting stale RAM.

### Weak properties

- The enabled `0x40..0x47` family has no visible address allowlist or per-command authentication.
- `0x200..0x202` is always initialized and contains generic pointer-based read/write operations without the `ecu_unlocked` check.
- The misleading getter name and status return make incorrect security review likely.
- The unlock magic is static and calibration-resident, not cryptographic or vehicle-unique.
- The gate controls one mailbox family, not all engineering/calibration functionality.

### Defensive recommendations

1. Block fixed IDs `0x40..0x47` and `0x200..0x202` at any gateway that exposes the ECU to untrusted or remote-origin traffic; allow them only on isolated service equipment.
2. Treat a calibration containing the four-byte pattern as a development artifact. Production release checks should reject it before signing/programming.
3. Add explicit address allowlists to all pointer-based read/write operations and prohibit peripheral, vector-table, executable, and security-state ranges.
4. Apply the same authenticated session gate to `0x202` that protects persistent programming, or compile the service out of production builds.
5. On a future firmware revision, derive mailbox permissions from a signed production policy field rather than four static bytes, and record access attempts diagnostically.
6. When auditing a vehicle, verify both persistent calibration bytes and runtime mailbox masks; checking only `ecu_unlocked` is incomplete.

## 9. Confidence and unresolved items

### Confirmed

- Calibration copy, CRC timing, exact four-byte predicate, and one-shot boot evaluation.
- `get_ecu_locked_state` returns `ecu_unlocked` through its output pointer.
- Conditional MB15 interrupt enable plus handler-entry recheck.
- Raw read/write capability classes for IDs `0x40..0x47`.
- Fixed request/response roles for `0x202`, `0x200`, and `0x201`.
- Pointer-based memory/calibration/logger operations in the `0x202` interpreter without the named unlock check.
- Bootloader programming-session and SecurityAccess requirements for persistent calibration programming.

### Inferred but strong

- The four-byte pattern is a development/engineering calibration signature.
- A calibration reflash containing the pattern will persistently enable the application engineering family on the next successful application boot.
- The un-gated `0x202` pointer writes can reach security-relevant live RAM because no general allowlist is visible.

### Unresolved

- Whether an MPU/firewall configuration blocks any address classes despite the absence of software checks.
- Whether vehicle gateway filtering prevents these fixed IDs from reaching the ECU outside a bench/service topology.
- The original tooling names and authentication assumptions for `0x200..0x202`.
- Whether a production calibration-signing/release pipeline independently rejects the magic pattern.
- Whether any undecompiled application diagnostic callback can modify `BOOL_40003331`; no such callback affects `ecu_unlocked` in the current cross-reference.

## Bottom line

The application engineering gate is not a normal diagnostic unlock. It is a boot-time policy bit derived from four static bytes in persistent calibration, then used to enable and recheck an otherwise unrestricted raw-memory CAN family. Normal application OBD does not enable it, and bootloader UDS SecurityAccess is a separate programming permission. Persistently changing the application state requires installing a calibration whose four bytes satisfy the predicate and rebooting.

The larger defensive concern is that the always-initialized `0x200/0x201/0x202` tooling channel also exposes pointer-based live-memory and calibration-shadow operations without consulting `ecu_unlocked`. It does not by itself prove persistent reflash or a complete post-boot unlock, but it means the four-byte gate is not a comprehensive engineering-interface boundary. Both proprietary CAN families should be isolated, authenticated, and address-constrained in any production exposure model.
