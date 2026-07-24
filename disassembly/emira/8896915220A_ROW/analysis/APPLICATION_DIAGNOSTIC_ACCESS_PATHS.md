# Emira Application Diagnostic Access Paths

## Scope and safety boundary

This report traces diagnostic access implemented by the **main Emira application** in `emira.c`. It is intentionally separate from the bootloader programming protocol described in `BOOTLOADER_PROGRAMMING_ANALYSIS.md`.

The focus is authorization architecture: transport routing, standard OBD versus extended-service dispatch, session and security state, resource-level permission checks, operating-condition gates, and reset/timeout behavior. It does not reproduce a working seed/key procedure, privileged request sequence, or programming recipe.

Confidence labels:

- **Confirmed** — direct control flow and state access in the recovered application.
- **Inferred** — behavior matches a standard diagnostic concept, but transport identity or exact naming is not fully recovered.
- **Unknown** — not safely attributable from the present export.

## Executive summary

The application contains two co-resident diagnostic service families behind a segmented request/response transport:

1. an OBD-oriented dispatcher for services `0x01..0x0a` (excluding obsolete Mode 05), including current data, freeze frames, stored/pending/permanent DTC views, clear emissions data, monitor results, control operations, and vehicle information;
2. an extended UDS-style dispatcher for `0x10`, `0x11`, `0x14`, `0x19`, `0x22`, `0x27`, `0x2e`, `0x2f`, `0x31`, and `0x3e`.

The extended path has layered authorization:

- session state `DAT_40001f36` with numeric states `1`, `2`, and `3`;
- security level `DAT_40007195`, normally `0`, with two unlock levels supported;
- failed-key counters and a delay timer;
- per-resource session and security masks in table metadata;
- request length/subfunction checks;
- operating-condition checks for reset, session transition, and writes;
- asynchronous/pending handling for long-running transitions and routines.

Returning to the base session clears security and cancels/cleans outstanding privileged activity. The application does not expose raw arbitrary-memory read/write through the visible service front ends: `0x22`, `0x2e`, `0x2f`, and `0x31` resolve identifiers through compiled resource tables and invoke registered callbacks.

The physical application diagnostic CAN request/response arbitration IDs are not proven. The transport consumes reassembled payload state around `0x4001c3d8` and observes controller/status registers at `0xfffc03d0/0xfffc03e0`, but no defensible ID mapping was recovered. Application CAN frames `0x202` and `0x40..0x47` on the separately identified controller must not be assumed to be this diagnostic route.

## Architecture

```text
CAN/segmented transport
        │
        ├── addressing/connection context 1 ──> OBD services 01..0A
        │                                      (PID/DTC/monitor registries)
        │
        └── addressing/connection context 2 ──> extended dispatcher
                                                │
                                                ├── session state
                                                ├── security level + delay/counters
                                                ├── resource session/security masks
                                                ├── operating-condition checks
                                                └── DID/routine/IO callbacks
```

The transport state machine reassembles single- and multi-frame requests, enforces sequence and buffer bounds, dispatches only after a complete request, segments responses, and supports pending/timeouts (`emira.c:69865-70300`). OBD responses use a short timeout (`0x19` in the recovered state), while the extended path uses a longer `1000`-count window (`emira.c:70178-70199`, `70255-70297`). Exact tick units are not established.

## Transport and CAN route

`FUN_00a68940()` processes receive state from `0x4001c3d8..0x4001c3e2`, including payload length, addressing context, frame type, sequence number, and transport direction. It handles:

- single frames;
- first/consecutive multi-frame assembly;
- flow-control state;
- response segmentation and retry/pending behavior;
- connection-specific timeouts (`emira.c:69865-70300`).

The response builders `FUN_00a68150()` and `FUN_00a68198()` feed the common transmit service `FUN_00a668e0()` (`emira.c:69437-69467`). The physical CAN controller status observed by this stack includes `DAT_fffc03d0` and `DAT_fffc03e0` (`emira.c:69880-69901`).

What is **not** confirmed:

- application diagnostic request and response arbitration IDs;
- whether addressing contexts `1` and `2` are exactly “functional” and “physical” rather than internal connection classes;
- which physical CAN instance or gateway path exposes this stack at the vehicle connector.

The bootloader's proven request/response IDs and programming state machine are independent evidence and must not be projected onto this application stack.

## Standard OBD path

The OBD dispatcher switches directly on service values `1..10` and invokes a dedicated worker (`emira.c:68690-68801`, duplicated transport path at `70130-70200`):

| Service | Worker | Confirmed application behavior |
|---:|---|---|
| `0x01` | `FUN_00a66a60` | Current-data PID requests through the 59-entry Mode 01 registry. |
| `0x02` | `FUN_00a66c90` | Freeze-frame PID/frame data through secondary PID callbacks. |
| `0x03` | `FUN_00a66f38` | Enumerates one stored/confirmed DTC view (`emira.c:67868-67904`). |
| `0x04` | `FUN_00a67028` | Clears emissions diagnostic information through shared clear-all group `0xffffff` (`emira.c:67909-67925`). |
| `0x06` | `FUN_00a670d8` | On-board monitor test results from a separate registry. |
| `0x07` | `FUN_00a672e4` | Enumerates a second DTC-status view. |
| `0x08` | `FUN_00a673fc` | Table-driven control operation; individual requests add their own conditions. |
| `0x09` | `FUN_00a67704` | Vehicle-information service family. |
| `0x0a` | `FUN_00a6695c` | Permanent-style DTC view. |

Mode 05 is absent from the switch. Unsupported service values return a service-not-supported-style result.

Mode 01 is bounded by `obd_ii_handlers_mode01[59]`. `obd_pid_lookup_routine___()` binary-searches this table; each row provides the PID, read/freeze-frame callbacks, size, and availability metadata (`emira.c:83927-83957`, `85110-85159`). This is a compiled allowlist, not arbitrary memory access.

The OBD path is intentionally distinct from the extended session/security path. It does not require the application security level used by `0x27`, although individual Mode 08/control callbacks can enforce engine-stopped or other conditions. Mode 04 and extended `0x14` converge on the same DTC manager but have different request formats and front-end gates.

## Extended service dispatcher

The extended dispatcher is explicit in both recovered copies of the transport state machine (`emira.c:68818-68862`, `70216-70251`):

| Service | Worker | Role |
|---:|---|---|
| `0x10` | `FUN_00a4e078` | Diagnostic session control. |
| `0x11` | `FUN_00a4e318` | ECU reset/lifecycle reset. |
| `0x14` | `FUN_00a890e0` | Clear diagnostic information by 24-bit group. |
| `0x19` | `FUN_00a897b8` | Read DTC information and records. |
| `0x22` | `FUN_00a898e0` | Read data by identifier. |
| `0x27` | `FUN_00a4e49c` | Security access. |
| `0x2e` | `FUN_00a89a8c` | Write data by identifier. |
| `0x2f` | `FUN_00a9c1e8` | Input/output control by identifier. |
| `0x31` | `FUN_00a67c80` | Routine control. |
| `0x3e` | `FUN_00a67de4` | Tester present/session keepalive. |

The dispatcher also maintains per-service invocation/retry statistics for these services and at least `0x28` and `0x85` (`FUN_00a67f10`, `emira.c:69362-69428`). The latter two appearing in accounting does not prove that they are enabled in the visible request switch.

Common negative-result categories correspond to unsupported service/subfunction, malformed length, conditions not correct, request out of range, security denied, sequence error, delay active, programming/general failure, and response pending. The service workers validate these independently rather than relying only on the top-level dispatcher.

## Session state

### State variables

The actual application diagnostic session is `DAT_40001f36`:

- initialized to `1` by `FUN_00a4e2e0()` (`emira.c:65000-65005`);
- read by `FUN_00a4e2fc()` (`emira.c:65010-65015`);
- changed by service `0x10` (`emira.c:64896-64995`).

This must not be confused with `DAT_4000b990`, returned by `get_diag_session_state()`. The latter gates whether the internal DTC manager is in its active runtime state (commonly value `2`), and is used by DTC query/update APIs (`emira.c:78679-78683`, `83879-83888`). It is not the UDS-style diagnostic session selected by service `0x10`.

### SessionControl behavior

`FUN_00a4e078()` accepts numeric subfunctions `1`, `2`, and `3`, checks suppress-positive-response handling, message length, and transport context, and returns timing parameters with accepted transitions (`emira.c:64904-64976`).

Observed behavior:

- state/subfunction `1` is the base session; returning to it invokes common session cleanup;
- subfunction `3` enters the session required by `0x27`, `0x2e`, `0x2f`, and many `0x31` resources;
- subfunction `2` is guarded by `FUN_00a4dfe4()` and handled asynchronously with response-pending behavior before a lifecycle transition (`emira.c:64918-64933`, `64981-64990`).

The standard labels “default,” “programming,” and “extended” are plausible for `1/2/3`, but only the numeric behavior is confirmed. In particular, the application-side subfunction-2 transition is not the bootloader download protocol itself.

`FUN_00a4dfe4()` requires a stopped-engine condition and a second low-state threshold (`FUN_00a0f338() == 0` and `DAT_40003666 < 300`) (`emira.c:64856-64863`). This gate is reused by reset and write paths.

### Timeout and cleanup

`FUN_00a4e018()` loads a `5000`-count session timer. `FUN_00a4e028()` decrements it and invokes common cleanup on expiration (`emira.c:64867-64891`). Tester Present `0x3e` refreshes/maintains this lifecycle when request format, current session, security state, and addressing context are accepted (`emira.c:69296-69332`).

`FUN_00a68914()` performs the common return-to-base operation: if not already in state `1`, it clears security, restores session `1`, and cancels tracked I/O/routine state (`emira.c:69849-69860`). This prevents privilege from surviving a session timeout or ordinary session return.

## SecurityAccess

### Two levels and session gate

`FUN_00a4e49c()` implements service `0x27`. It is accepted only when diagnostic session state equals `3`; requests in other sessions clear pending challenge state and are rejected (`emira.c:65092-65136`).

The subfunction mapper supports two challenge/response levels. The current authorized level is `DAT_40007195`; pending challenge subfunction is `DAT_40007196`; challenge material is held in `DAT_40007198` (`emira.c:65114-65125`).

The challenge path obtains fresh platform-derived material and calls a level-specific transformation. The response path requires the expected sequence, compares the response with the internally derived value, and sets the authorized level only on success (`emira.c:68361-68388`, `69107-69201`). Exact constants and transformation steps are intentionally not reproduced here.

### Attempt limiting and delay

Failed or malformed responses:

- clear pending challenge state;
- increment level-specific failure counters through `FUN_00a4e610()`;
- return invalid-key or exceeded-attempt-style results;
- arm a delay through `FUN_00a4e63c()` after the threshold (`emira.c:69117-69152`, `65173-65196`).

`DAT_40001f38` is the active delay counter. A new challenge request receives a delay-active result until it expires (`emira.c:69172-69187`, `65141-65151`, `65201-65205`). Successful authorization clears the pending challenge and the relevant failure counter.

Defensive implication: possession of an accepted diagnostic session is insufficient; privileged resources can require one of two distinct security levels, and repeated guessing is rate-limited.

## ReadDataByIdentifier (`0x22`)

`FUN_00a898e0()` validates transport context, session/security ranges, request parity/length, and then processes one or more 16-bit identifiers (`emira.c:86097-86176`). Reads resolve through `FUN_00a88f28()`:

- a compiled generic DID table via `FUN_00aad774()`;
- selected ranges that bridge to OBD PID, monitor-test, or other registered handler tables;
- callback existence and output-size checks before invocation (`emira.c:85193-85254`).

The service therefore exposes only registered resources. Some DIDs are aliases/adapters over existing OBD data; others use dedicated application callbacks. The complete DID-number-to-resource dictionary is not exported in this pass, so individual identifiers are not guessed.

Read access still passes common session/security range validation in the service front end. Individual callbacks can additionally reject current operating conditions or unavailable data.

## WriteDataByIdentifier (`0x2e`)

`FUN_00a89a8c()` requires:

- diagnostic session state `3`;
- accepted addressing/transport context;
- valid request length;
- a DID present in the compiled resource table;
- a registered write callback;
- the general stopped-engine/low-state condition through `FUN_00a89070()` and `FUN_00a4dfe4()` (`emira.c:86183-86242`, `85259-85278`).

The DID callback receives a copied bounded payload rather than a caller-supplied raw pointer. Return values are translated to conditions, range, security, and programming-failure categories. No raw address-and-length memory writer is visible in this service.

Some writable resources update the separate 160-byte coding/configuration image at RAM `0x40009120`, whose changes are tracked by `DAT_40003914` and saved to flash `0x30000` during an orderly lifecycle. That persistence path is separate from the learned-data store. Exact DID ownership of every coding field remains to be table-extracted.

## RoutineControl (`0x31`)

`FUN_00a67c80()` parses a routine subfunction plus a 16-bit routine identifier, validates request length and transport context, and resolves the identifier through a resource table (`emira.c:69206-69291`). The generic resource dispatcher `FUN_00aa86f4()` adds critical per-resource authorization:

- resource must exist and have a callback;
- selected diagnostic session must be present in the resource's session mask;
- current security level must be present in the resource's security mask;
- callback-specific request and operating-condition validation must succeed (`emira.c:99569-99711`).

The dispatcher supports start, stop, and result-like callback modes and tracks a bounded set of outstanding/asynchronous routines. Long-running work can return pending and later complete through the transport response state machine. Session cleanup cancels tracked activity.

This is a capability registry, not a generic function-call endpoint. The complete routine-ID list and side effects require exporting its metadata table; they are not inferred from numeric IDs alone.

## InputOutputControl (`0x2f`)

`FUN_00a9c1e8()` is also restricted to session `3`, validates length/context, resolves a registered I/O-control resource, and translates callback results to standard diagnostic result categories (`emira.c:93957-94022`). Outstanding controls are tracked in a ten-entry list and restored/cancelled by `FUN_00a9c170()` on session cleanup (`emira.c:93931-93950`).

The resource callback layer adds its own session/security/conditions checks. This prevents service `0x2f` from being a general arbitrary-actuator primitive even after entering an extended session.

## ClearDiagnosticInformation (`0x14`)

`FUN_00a890e0()` requires an exact three-byte group, valid transport context, an allowed session/security combination, and a recognized 24-bit group (`emira.c:85285-85321`). Supported broad identifiers include `0xffffff` and `0xfff000`; ordinary group values are range-validated before reaching the shared clear core.

The call delegates to `FUN_00a754a0()`, which clears through the same 258-slot DTC manager used by OBD Mode 04. Result mapping distinguishes unsupported group, conditions, asynchronous/busy state, and erase/general failure. This service does not erase application code or arbitrary flash.

## ReadDTCInformation (`0x19`)

`FUN_00a897b8()` validates current session/security/context and dispatches supported subfunctions to helpers for:

- DTC counts/status masks;
- DTC identifier/status enumeration;
- snapshot/freeze-frame-style records;
- extended data records;
- selected aggregate/status information (`emira.c:85568-86090`).

The manager bounds output to the response buffer and limits enumerated records. The recovered switch is partially damaged by decompilation, so the precise standard name of every subfunction is not claimed. The service reads the same persistent compact, auxiliary, snapshot, and event banks documented in `DIAGNOSTIC_MONITOR_FRAMEWORK_ANALYSIS.md` and `LEARNED_DATA_PERSISTENCE_ANALYSIS.md`.

## ECUReset (`0x11`)

`FUN_00a4e318()` accepts only its supported reset subfunction, checks session and security masks, enforces the stopped-engine/low-state condition, and supports suppressed or delayed positive response (`emira.c:65020-65087`). Before reset it:

- saves the learned image unless default reconstruction is active;
- writes the separate coding store if dirty;
- invokes the hardware/software reset path (`emira.c:65044-65053`, `65070-65082`).

Defensive implication: reset is not merely a transport action; it is conditioned on safe operating state and preserves validated nonvolatile state before execution.

## Access-control matrix

| Service/resource class | Base session | Session `3` | Security level | Operating/resource gates |
|---|---|---|---|---|
| OBD `0x01/02/03/06/07/09/0a` | Available through OBD dispatcher | Independent of extended unlock | No application `0x27` level required | Compiled PID/monitor/DTC tables and callback availability. |
| OBD `0x04` | Available through OBD dispatcher | Independent of extended unlock | No application `0x27` level required | Shared DTC-clear manager conditions. |
| OBD `0x08` | Available by compiled test table | Independent of extended unlock | No general `0x27` requirement | Per-test safety gates, including stopped-engine conditions where configured. |
| Extended `0x10` | Controls session transition | Controls return/other transitions | Transition-dependent | Length, subfunction, transport context, engine-stopped gate for state `2`. |
| Extended `0x27` | Rejected outside state `3` | Required | Establishes level 1 or 2 | Expected sequence, response validation, attempts and delay. |
| Extended `0x22` | Resource-dependent | Resource-dependent | Resource/session masks | Compiled DID and read callback only. |
| Extended `0x2e` | Not accepted | Required | Resource-dependent | Compiled DID/write callback, length, stopped-engine/low-state check. |
| Extended `0x2f` | Not accepted | Required | Resource-dependent | Registered I/O resource, session/security masks, callback conditions. |
| Extended `0x31` | Resource-dependent | Common privileged context | Per-routine mask | Registered routine, mode, request length, callback conditions, bounded pending tracking. |
| Extended `0x14/0x19` | Allowed combinations are mask-driven | Allowed combinations are mask-driven | Service/resource checks | Valid DTC group/subfunction and shared manager bounds. |
| Extended `0x11` | Mask-driven | Mask-driven | Service mask | Supported reset type, safe operating state, NVM save. |
| Extended `0x3e` | Session lifecycle only | Maintains active session | Current security included in service checks | Exact subfunction/length/context. |

Numeric session/security bitmasks are checked in several workers with expressions such as `mask >> current_state`. The table above intentionally states the proven policy rather than publishing a request sequence.

## Defensive implications

### Strengths visible in the binary

- OBD, DID, routine, and actuator access are table-allowlisted.
- Session and security are separate; session transition alone does not grant privilege.
- Two security levels permit different capability sets.
- Pending challenge sequence is tracked and invalid order is rejected.
- Failure counters and a delay timer rate-limit repeated authorization attempts.
- Per-resource session/security masks remain in force after global unlock.
- Dangerous lifecycle/write actions add engine-stopped and state checks.
- Requests are length-, range-, transport-context-, and callback-validated.
- Long-running operations are bounded and tracked rather than blocking transport indefinitely.
- Privilege and outstanding I/O/routines are cleared on session timeout/return.
- Reset saves learned/coding state before execution when safe.

### Residual risks and review priorities

- The authorization transformation is implemented locally in application code; its cryptographic strength and key uniqueness require a separate confidential review.
- Security state is global RAM state. Concurrent diagnostic connection behavior should be tested to confirm authorization is bound to the initiating connection context.
- Several session/security masks are compact table bytes. Exporting and reviewing every resource row is necessary to detect accidentally permissive entries.
- Suppress-positive-response and asynchronous flows add state complexity; fuzzing should focus on cleanup, timeout, and cross-service interleaving without attempting unauthorized access.
- The physical application CAN route and gateway policy are unknown. Network-level exposure cannot be assessed until arbitration IDs and gateway filtering are established.
- Bootloader security must be assessed separately; a strong application gate does not imply equivalent programming-path policy, or vice versa.

## Confirmed versus unknown

### Confirmed

- Distinct OBD and extended service dispatchers.
- Extended services `0x10`, `0x11`, `0x14`, `0x19`, `0x22`, `0x27`, `0x2e`, `0x2f`, `0x31`, and `0x3e`.
- Session state `DAT_40001f36` and separate security level `DAT_40007195`.
- Two challenge/response levels, sequence tracking, attempt counters, and delay.
- Table-driven DID, routine, I/O, PID, and monitor resources.
- Per-resource session and security masks.
- Safe-state gating for session `2`, reset, and DID writes.
- Session timeout cleanup and privilege revocation.
- Common DTC manager behind OBD Mode 04 and extended `0x14`.

### Unknown or incomplete

- Physical application diagnostic CAN request/response IDs and gateway route.
- Exact standard names for numeric session states `2` and `3`.
- Complete DID, routine, and I/O-control dictionaries.
- Which resource rows require security level 1 versus level 2.
- Whether any extended services not present in the visible switch are enabled through another route.
- Multi-client ownership semantics for global session/security state.
- Cryptographic quality, manufacturing key provisioning, and fleet-level key diversity.

## Next work

1. Export the generic DID table resolved by `FUN_00aad774()` with read/write callback pointers and sizes.
2. Export the routine registry resolved by `FUN_00aa8644()` including session/security masks.
3. Export the I/O-control registry and its restore callbacks.
4. Trace `FUN_00a668e0()` to the final CAN message-buffer configuration to establish application diagnostic arbitration IDs and addressing contexts.
5. Review global-versus-connection ownership of `DAT_40001f36` and `DAT_40007195` under overlapping transport connections.
6. Bench-validate allowed/denied service behavior using non-mutating reads and controlled safe-state tests; keep programming/security algorithm evaluation separate and confidential.

## Bottom line

The Emira application exposes a mature, layered diagnostic stack. Normal OBD access is registry-bounded and independent of the privileged extended session. Extended reads, writes, routines, I/O control, clears, and reset actions pass through session, security, resource metadata, request validation, and operating-condition gates. Security unlock is two-level, stateful, attempt-limited, and revoked on session cleanup. The main remaining architectural unknown is not service coverage but external exposure: the physical application diagnostic CAN route and the exact per-resource permission tables still need extraction.
