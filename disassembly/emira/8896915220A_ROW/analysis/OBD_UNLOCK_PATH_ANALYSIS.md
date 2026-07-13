# Emira OBD/DLC unlock-path analysis

## Scope and safety boundary

This report consolidates every code path that could increase ECU authority through a vehicle diagnostic connection. “OBD2” is treated as the physical DLC and its reachable CAN networks, not only legislated OBD Modes 01–0A.

The analysis is defensive. It maps gates, state transitions, authority and possible inconsistencies without publishing seed/key transforms, raw request payloads, writable-address recipes or a procedure for bypassing authorization.

## Executive result

There is no single ECU-wide unlock. The firmware implements at least three independent security domains:

1. **Application extended-diagnostic authorization** — two SecurityAccess levels grant table-defined DID, routine and I/O privileges. They do not expose arbitrary memory or application flash programming.
2. **Bootloader programming authorization** — a separate SecurityAccess implementation grants descriptor-bounded erase/program authority over calibration, application and selected metadata regions.
3. **Application engineering authorization** — a boot-time calibration predicate enables a raw-memory CAN family. It is not set by either application or bootloader SecurityAccess.

Ordinary OBD Modes 01–0A do not unlock any of these domains.

Two defensive findings deserve priority:

- Boot RoutineControl erase admission lacks the explicit common-unlock check used by RequestDownload, although lower descriptor/range/blank-state constraints remain.
- The application’s always-initialized `0x200/0x201/0x202` tooling channel contains generic pointer-based live-memory operations without the named engineering-unlock check. If this CAN controller is reachable from the DLC or another untrusted network, the four-byte engineering gate is not a complete boundary.

## Reachability caveat

Only the bootloader’s CAN route is presently identified end to end: request `0x730`, response `0x630`, with secondary request `0x7ff` also accepted. The main application’s normal extended-diagnostic request/response IDs are stored in missing descriptor initializers and remain unknown.

The fixed engineering/tooling IDs live on application FlexCAN base `0xc3e60000`. No checked-in pinout, gateway policy or capture proves that this controller is exposed at the vehicle DLC. Therefore:

- code-level remote capability is confirmed;
- DLC-level reachability of application UDS and proprietary tooling remains unresolved;
- gateway filtering may materially reduce exposure, but must be measured rather than assumed.

## Authority map

| Path | Entry condition | Authorization result | Capability after authorization | Persistence |
|---|---|---|---|---|
| Legislated OBD Modes 01–0A | OBD dispatcher | none | allowlisted PIDs, DTC views/clear, monitor results and table-driven Mode 08 controls | DTC clear/control side effects only |
| Application extended UDS-like path | application diagnostic session `3` | SecurityAccess level 1 or 2 | resource-table-limited reads, writes, routines and I/O control | selected coding writes can persist |
| Application session-`2` transition | stopped engine and low-state condition | no direct unlock; asynchronous lifecycle transition | programming/handoff-like state transition, exact external destination unresolved | transition/reset dependent |
| Bootloader UDS programming | boot session `2` | either boot SecurityAccess family sets one common unlock bit | descriptor-bounded erase/download/program plus protected DID access | flash persistent |
| Boot RoutineControl erase | boot session `2` or `3` | no explicit common-unlock check in visible admission path | descriptor-bounded erase worker | destructive/persistent if reachable descriptor succeeds |
| Application engineering family `0x40..0x47` | calibration-derived engineering flag plus mailbox enable | static boot-time predicate, not challenge-response | unrestricted live reads/writes in visible software; no address allowlist | RAM/peripheral effects; raw stores do not program flash |
| Proprietary tooling `0x200..0x202` | controller initialized; no named unlock check | interpreter state only | pointer-based live memory/calibration-shadow operations and logger control | normally volatile; no calibration-flash writer found |

## Path 1: application extended diagnostics

The application has separate OBD and extended dispatchers. The extended dispatcher supports services corresponding to session control, reset, DTC clear/read, DID read/write, SecurityAccess, I/O control, RoutineControl and TesterPresent.

The relevant state is:

| State | Role |
|---|---|
| `DAT_40001f36` | application diagnostic session, normally `1`; privileged SecurityAccess requires state `3` |
| `DAT_40007195` | current application security level, `0`, `1` or `2` |
| `DAT_40007196` | pending challenge family |
| `DAT_40007198` | current challenge material |
| `DAT_40001f38` | failed-attempt delay counter |

SecurityAccess is accepted only in session `3`. Two challenge/response levels are implemented and remain distinct after authorization. Failed attempts increment per-level counters and can arm a delay. Returning to base session, session timeout or common cleanup clears security and cancels outstanding privileged controls.

Authorization is layered rather than global. DID, routine and I/O registries carry session/security masks and callback pointers. A valid security level permits only resources whose metadata includes that session and level; callbacks add length, range and operating-condition checks. WriteDataByIdentifier and reset paths also require stopped-engine/low-state conditions where configured.

No visible application UDS service accepts an arbitrary address and length, and the application has no general calibration-flash writer. Application SecurityAccess therefore does **not** equal reflash authorization.

### Application session `2`

Session-control subfunction/state `2` is accepted only with the engine stopped and another low-state threshold satisfied. It uses asynchronous response-pending handling and a lifecycle transition worker. The code shape is consistent with a programming-session handoff or reset preparation, but this export does not prove the final CAN route or boot-entry marker well enough to describe it as a complete bootloader-entry procedure.

Defensive implication: this transition is a likely bridge worth validating on a bench, but it is not itself a security bypass and should not be conflated with the bootloader’s independently implemented session `2`.

## Path 2: bootloader SecurityAccess and programming

The bootloader implements its own CAN/ISO-TP stack, diagnostic sessions and security state. Application security level does not carry into it.

Boot session `2` is conclusively the programming session. SecurityAccess supports two seed/key families, but successful verification of either sets the same common authorization bit `DAT_400018de`. The visible protected services do not differentiate which family produced the unlock.

The state machine includes:

- selected family and seed-issued sequence markers;
- a finite attempt counter;
- invalid-key, exhausted-attempt and delay-active responses;
- unlock revocation on every committed session change;
- inactivity timeout for sessions `2` and `3`.

After unlock, RequestDownload, every TransferData block and RequestTransferExit repeat the programming-session/common-unlock checks. Authority remains capability-bounded:

- RequestDownload start must exactly match one of 15 region descriptors;
- declared length and descriptor capacity are enforced;
- some metadata regions are blank-only/one-time;
- block sequence and declared remaining length are checked;
- flash-driver success, transfer CRC and RSA/SHA boot authenticity are separate decisions.

This is the legitimate persistent path to calibration or application replacement. A calibration containing the application engineering predicate can make engineering mode active on the next successful boot, but boot SecurityAccess does not directly set the application flag.

## Path 3: Boot RoutineControl erase inconsistency

The boot RoutineControl handler admits an erase routine in session `2` or `3` and invokes the same descriptor-based erase worker used by RequestDownload. Unlike RequestDownload, the visible admission path does not explicitly test the common SecurityAccess-unlocked bit.

This is a potential authorization inconsistency, not proof of arbitrary erase:

- the requested base still must match the descriptor table;
- region flags and blank-only policy still apply;
- lower flash drivers can reject invalid or protected ranges;
- the raw descriptor contents are not rendered completely in the C export.

It should nevertheless be treated as the highest-priority boot-path validation because erase is destructive even when subsequent programming remains locked.

## Path 4: persistent application engineering mode

At application startup, calibration flash is copied to the 64 KiB RAM shadow. Four dispersed calibration bytes are checked once; when all match, `ecu_unlocked` becomes true. FlexCAN initialization then enables the mailbox interrupt for IDs `0x40..0x47`, and the handler rechecks the same boolean before dispatch.

Once enabled, the family implements caller-addressed live reads and writes with no visible software address allowlist. It can access RAM and memory-mapped peripherals; raw CPU stores to flash addresses do not substitute for flash-driver erase/program operations.

The flag is volatile. Persistent enablement comes from the signed/programmed calibration bytes, not coding or learned data. Normal application UDS has no setter and never reruns the predicate. Bootloader programming can change it only indirectly by installing a matching calibration and rebooting.

## Path 5: un-gated proprietary tooling channel

The same application controller always initializes:

- request ID `0x202`;
- response/stream IDs `0x200` and `0x201`.

Its interpreter supports internal pointer selection, pointer-based reads/writes, calibration flash-versus-RAM-shadow selection, calibration shadow copying and logger configuration. Neither its receive queue nor command interpreter calls `get_ecu_locked_state`, and no general address allowlist is visible.

This path changes the threat model:

- It can modify live RAM without satisfying application UDS SecurityAccess or the boot-time engineering predicate.
- Editing only the four RAM-shadow predicate bytes does not rerun the predicate.
- Editing only `ecu_unlocked` after startup does not repeat the conditional mailbox-interrupt initialization.
- Nevertheless, generic pointer writes can reach other security-relevant RAM or peripheral state unless an unseen MPU/firewall blocks them.
- The channel already provides live-memory authority of its own, so enabling `0x40..0x47` is not required for it to be security significant.

A complete runtime escalation chain is **not confirmed** because physical CAN reachability, interpreter state prerequisites, MPU policy and target-register effects remain unverified. From a defensive perspective, `0x202` should be treated as an authentication bypass candidate and isolated immediately.

## What ordinary OBD can and cannot do

| Operation | Ordinary OBD Modes 01–0A |
|---|---|
| Read current/freeze-frame/vehicle information | Yes, through compiled registries |
| Read stored/pending/permanent DTC views | Yes |
| Clear emissions DTC/history | Yes, through shared manager policy |
| Run configured Mode 08 controls | Only allowlisted tests with callback conditions |
| Enter application SecurityAccess | No; that belongs to the separate extended dispatcher |
| Program application/calibration flash | No |
| Set `ecu_unlocked` | No |
| Enable raw `0x40..0x47` engineering mailbox | No |

The physical DLC may still carry extended or proprietary CAN traffic. The distinction is service architecture, not connector shape.

## Defensive validation plan

1. Capture all DLC CAN buses during normal diagnostics and identify whether boot `0x730/0x630`, application extended UDS and `0x200..0x202` are gateway-reachable.
2. Export the application DID/routine/I/O registries and their session/security masks; audit for resources permitted at security level `0` or unintended level `1`.
3. Decode the boot 15-entry region table and 12-entry DID table directly from a canonical binary.
4. Validate the RoutineControl erase authorization mismatch only on a sacrificial ECU or hardware simulator, using a noncritical descriptor first.
5. Audit MPU/firewall configuration for protection of vectors, executable RAM, security state and peripheral control registers from application tooling writes.
6. Remove or authenticate `0x200..0x202` in production firmware; at minimum apply strict address allowlists and gateway filtering.
7. Reject production calibration packages containing the engineering predicate and verify mailbox masks after boot.
8. Confirm diagnostic security state is bound to the initiating transport connection rather than shared globally across concurrent clients.

## Bottom line

The normal persistent-programming route is clearly boot programming session, boot SecurityAccess, and descriptor-bounded reflash. The application has a safe-state-gated asynchronous session-`2` transition that is a strong candidate for the supported handoff into that environment, but its final boot-entry marker remains to be confirmed. Application UDS SecurityAccess is a separate two-level authorization system for table-registered resources and does not unlock programming or raw memory.

There are two likely security weaknesses requiring bench validation: boot RoutineControl erase lacks the explicit unlock check used by RequestDownload, and the application `0x202` tooling channel appears to expose generic live-memory operations without the named engineering gate. Whether either is exploitable from the physical OBD connector depends on network routing and hardware memory protection, which the current repository cannot establish.
