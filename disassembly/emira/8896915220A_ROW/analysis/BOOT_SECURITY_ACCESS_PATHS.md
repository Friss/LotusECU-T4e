# Emira G6 Boot Security-Access Paths

## Scope and safety boundary

This report audits the bootloader's externally reachable authorization state in `emira.c`. It
covers CAN/ISO-TP entry, diagnostic sessions, SecurityAccess state, retry/delay behavior, and the
authorization checks protecting erase, download, program, metadata-write, routine, and reset paths.

It is intentionally defensive. It does not reproduce tester payload sequences, the seed-to-key
algorithm, raw writable-region records, or instructions for bypassing the checks. Function addresses
and state variables are included so the implementation can be reviewed and hardened.

Confidence labels:

- **Confirmed** — a state test, transition, or authorization check is directly visible.
- **Inferred** — protocol meaning is strong but an external timing unit or table value is missing.
- **Unknown** — the export does not preserve enough data to decide.

## Executive findings

- The boot diagnostic surface is reachable on standard-ID CAN through physical request ID `0x730`
  and response ID `0x630`; `0x7FF` is also accepted into a separate queue. The physical/functional
  meaning of `0x7FF` remains unresolved.
- Session values `1`, `2`, and `3` are accepted. State `2` is conclusively the programming session;
  state `3` is extended-session-like. Every session transition clears the security unlock, seed
  sequence, transfer state, and asynchronous programming state.
- SecurityAccess exposes two request/response level pairs (`01/02` and `11/12`), but successful
  verification of either sets the same single authorization bit, `DAT_400018DE=1`. No protected
  service in the visible dispatcher distinguishes the two levels after unlock.
- RequestDownload, TransferData, and RequestTransferExit require both session `2` and the shared
  unlock bit. RequestDownload is additionally restricted to an exact descriptor-listed start
  address; TransferData is bounded by declared size, region length, and block sequencing.
- WriteDataByIdentifier uses per-DID permission metadata. Some DIDs are writable in programming
  session without security, while records carrying permission bit `0x20` additionally require the
  shared unlock bit. The descriptor table is not decoded well enough to publish a complete DID
  permission list.
- RoutineControl contains asynchronous erase (`0xFF00`), signature verification (`0x0205`), overall
  verification status (`0x0200`), and a stub-like routine (`0x0206`). The visible `0xFF00` handler
  permits session 2 or 3 but does not explicitly test `DAT_400018DE`. This is a defensive review
  finding, not proof of unconstrained erase: the lower erase worker still requires a descriptor
  match, blank-only policy where configured, and valid range/driver behavior.
- Attempt/delay controls are RAM state with a separately queried boot condition that can start a
  ten-tick delay. Exact tick duration and persistence semantics are not proved.

## External transport and dispatcher entry

The bootloader configures FlexCAN at `0xFFFC0000`. Two receive-mailbox handlers extract 11-bit IDs
and call `flexcan_process_730_7ff` (`emira.c:15722-15807`). That function accepts only eight-byte CAN
frames and separates:

- `0x730` into the primary request ring;
- `0x7FF` into a smaller secondary ring.

This is confirmed at `flexcan_process_730_7ff` (`0x008171E8`, `emira.c:17858-17911`). ISO-TP then
reassembles messages up to `0x1FF` bytes, checks consecutive-frame sequence, and discards malformed
or timed-out messages (`emira.c:17309-17798`). Segmented responses are sent on `0x630`
(`emira.c:17338`, `17376`, `17424`, `17481`).

The central diagnostic dispatcher is `FUN_00814D84` (`0x00814D84`,
`emira.c:17009-17103`). It routes services `0x10`, `0x11`, `0x22`, `0x27`, `0x28`, `0x2E`, `0x31`,
`0x34`, `0x36`, `0x37`, `0x3E`, and `0x85`. Unknown services receive NRC `0x11`.

Long-running requests are retained when a handler returns response-pending status. Repeated pending
responses are throttled by a `0x9C4`-tick check (`emira.c:17079-17103`). This is important for the
erase/program/signature paths: authorization is checked at request admission, then operation state
persists across dispatcher invocations.

## Security-relevant state model

| State | Role | Direct evidence |
|---|---|---|
| `DAT_400018BE` | current diagnostic session | checked by every session-restricted handler |
| `DAT_400018BF` | requested/new session | written by `FUN_008140B8`, committed by `FUN_00814C48` |
| `DAT_400018DE` | shared security-unlocked bit | set after valid key; cleared on session transition |
| `DAT_400018E0` | selected seed/key family, `1` or `2` | set by seed request and verified by matching key subfunction |
| `DAT_400018E2` | seed-issued / sequence-valid marker | required before key verification |
| `DAT_400018DF` | remaining key-attempt state | initialized to 2, decremented by invalid verification |
| `DAT_400018DD` | security delay counter | blocks normal seed issuance while nonzero |
| `DAT_400018DC` | expected TransferData block counter | initialized to 1 after download setup |
| `DAT_400018E3` | selected region-descriptor index | set only after exact descriptor-base match |
| `DAT_40001D08` | remaining declared download length | decremented as transfer payload is accepted |
| `DAT_40001CE6` | running transfer CRC-16 | initialized to `0xFFFF`, returned at transfer exit |
| `DAT_40001D14..17` | pending write/download/transfer/exit states | cleared on session transition |
| `DAT_400018D8` | pending RoutineControl selector | cleared on session transition |

`FUN_00814C48` commits a session and clears the unlock, seed selection/sequence, transfer, routine,
and pending-operation state (`emira.c:16978-17006`). It does **not** rewrite the attempt or delay
counters `DAT_400018DF/DD`. This is a strong boundary: an unlock is not intentionally portable
across a diagnostic-session change, while retry policy can survive that transition in RAM.

## DiagnosticSessionControl (`0x10`)

`FUN_008140B8` (`0x008140B8`, `emira.c:16720-16772`) accepts session subfunctions 1, 2, and 3.
Session 2 returns timing parameters and schedules `LAB_00814D60`, which commits the new session.
Transitions to other sessions may first run firmware validation and reset asynchronously.

The assignments support:

- state 1: default session;
- state 2: programming session (**confirmed by all programming gates**);
- state 3: extended-session-like state.

The names for 1 and 3 are standard-consistent inference. Entering state 2 or 3 arms a 5000-count
inactivity timer through `FUN_00815574`; state 1 disables it (`emira.c:17155-17170`). On expiration,
`FUN_00815728` either validates/resets toward application execution or resets protocol state
(`emira.c:17205-17235`). Exact wall-clock duration of the 5000 counts is unknown.

No security unlock is required merely to request session 2. Security is applied at the protected
service boundary.

## SecurityAccess (`0x27`) state machine

The handler is `FUN_008120A4` (`0x008120A4`, `emira.c:16197-16277`). It is accepted only in current
session 2; another session returns NRC `0x7E`.

### Seed admission

Two seed families are recognized:

- family 1: request selector `0x01`, matching key selector `0x02`;
- family 2: request selector `0x11`, matching key selector `0x12`.

A seed request stores family 1 or 2 in `DAT_400018E0`, marks the sequence in
`DAT_400018E2`, and sets a response length appropriate to a three-byte seed
(`emira.c:16202-16227`). `FUN_00819C68` obtains a nonzero internal 32-bit value and derives the
expected 24-bit result through `FUN_0081938C` (`emira.c:18535-18601`). This report deliberately does
not reproduce that deterministic transform.

If already unlocked, the handler returns a zero seed and leaves the common unlock state intact.
During an active delay it returns NRC `0x37`. Exhausted-attempt state produces NRC `0x36` and
reinitializes attempt accounting for the post-delay state (`emira.c:16202-16232`).

### Key verification

The matching response selector must correspond to the family that issued the seed. A mismatch
returns NRC `0x22`; a key without the seed-sequence marker returns NRC `0x24`; incorrect request
length returns `0x13` (`emira.c:16233-16264`).

Correct verification sets only:

```text
DAT_400018DE = 1  // common unlocked authorization
```

and returns a positive SecurityAccess response. Incorrect verification returns NRC `0x35` and
decrements attempt state. Once the remaining-attempt state is already exhausted, another mismatch
loads a ten-count delay (`DAT_400018DD=10`) (`emira.c:16241-16256`).

### Retry and timing behavior

`FUN_0081560C` initializes `DAT_400018DF=2`. Depending on boot entry reason,
`FUN_0080E3F8(1)` can cause initialization of `DAT_400018DD=10` (`emira.c:17217-17242`).
`FUN_00815854` decrements the delay counter (`emira.c:17283-17292`).

Confirmed behavior is therefore:

1. a finite RAM attempt state begins at 2;
2. invalid verification reduces that state;
3. exhausted-state behavior distinguishes NRC `0x36` from delay-active NRC `0x37`;
4. a delay counter can also be restored/initialized from a separate boot-state query.

Unknown boundaries:

- the real time represented by one delay tick;
- whether `FUN_0080E3F8` reads a nonvolatile failed-attempt marker, reset cause, or another boot
  policy bit;
- whether power cycling can always clear attempt history;
- whether separate hardware/debug access paths modify the same state.

## Security levels: authorization equivalence

The two SecurityAccess families select different seed/key internal state, but both set the same
single boolean `DAT_400018DE`. Searches through the boot diagnostic handlers find no subsequent test
of `DAT_400018E0` when authorizing erase, download, transfer, transfer exit, or protected DID access.

Therefore the visible authorization model is:

```text
family 01/02 --valid--> common unlocked bit
family 11/12 --valid--> common unlocked bit
                         |
                         +--> all services that test DAT_400018DE
```

It is **confirmed** that the visible handlers grant equivalent post-verification rights. It remains
**unknown** whether an omitted hardware security module, manufacturing wrapper, or external tester
policy gives the two families different practical exposure.

## Authorization matrix

| Operation | Session gate | Security gate | Additional boundary | Assessment |
|---|---|---|---|---|
| Enter session 2/3 (`0x10`) | any active diagnostic state accepted by dispatcher | none | validation/reset may be asynchronous | confirmed unauthenticated session entry |
| Read ordinary DID (`0x22`) | handler requires valid 3-byte request | generally none | descriptor or built-in DID list | confirmed |
| Read sensitive descriptor DID (`0x22`) | session 2 | common unlock | one special record is rejected outright | confirmed conditional read protection (`emira.c:16651-16696`) |
| Write DID (`0x2E`) | session 2 | only when descriptor permission bit `0x20` is set | exact DID descriptor, exact length, erase/program worker, other permission bits | confirmed per-record policy (`emira.c:16562-16648`) |
| RequestDownload erase/setup (`0x34`) | session 2 | common unlock | exact descriptor base, declared size, blank-only policy, async erase | confirmed (`emira.c:16279-16332`) |
| TransferData program (`0x36`) | session 2 | common unlock | active descriptor, expected block counter, declared remaining length, descriptor capacity | confirmed (`emira.c:16334-16380`) |
| RequestTransferExit (`0x37`) | session 2 | common unlock | exact request length; returns running CRC | confirmed; no separate signature gate (`emira.c:16382-16430`) |
| Routine erase (`0x31`, routine `0xFF00`) | session 2 or 3 | **no explicit common-unlock test in handler** | same descriptor-based erase worker and lower flash driver | confirmed missing handler-level check; effective reach requires validation (`emira.c:16799-16847`) |
| Signature check (`0x31`, routine `0x0205`) | session 2 or 3 | none | RSA policy/key provisioning and cache | confirmed (`emira.c:16848-16870`) |
| Overall verification (`0x31`, routine `0x0200`) | session 2 or 3 | none | marker/key/signature policy | confirmed (`emira.c:16871-16890`) |
| Routine `0x0206` | accepted before the session-2/3 branch in visible code | none | worker `FUN_0081388C` is a constant-success stub in export | confirmed code shape; external purpose unknown (`emira.c:16799-16815`, `16900-16917`) |
| ECU reset (`0x11`) | no explicit session gate beyond dispatcher/length | none | only reset subfunction 1 | confirmed (`emira.c:16938-16958`) |
| Communication/DTC control (`0x28`, `0x85`) | session 2 or 3 | none | limited subfunctions | confirmed (`emira.c:16920-16936`, `16961-16976`) |

### Defensive finding: RoutineControl erase

`FUN_00814380` admits routine `0xFF00` when current session is 2 or 3 and then calls the same
`FUN_00811824` erase worker used by RequestDownload. Unlike `FUN_00812554`, it does not test
`DAT_400018DE` before starting (`emira.c:16799-16847`).

This should be treated as a **potential authorization inconsistency**:

- It does not imply arbitrary-address erase. `FUN_00811824` requires an exact match in the 15-entry
  region table, observes blank-only flags, and dispatches only known erase classes
  (`emira.c:16036-16092`).
- It does imply that the explicit SecurityAccess gate present on `0x34` is absent from the visible
  `0x31/0xFF00` admission path.
- The highest-value validation is to decode the routine's permitted descriptor records and confirm
  behavior on a sacrificial bench ECU without modifying production hardware.

## WriteDataByIdentifier policy

`FUN_008138A4` (`0x008138A4`, `emira.c:16562-16648`) first resolves the DID through a 12-entry
descriptor table. Each record contains a destination, length, and permission flags. It rejects
records with disallowed flag combinations, requires exact payload length, and tests permission bit
`0x20` to decide whether `DAT_400018DE` is required.

Two special record classes are visible:

- a small fixed metadata record written through the `0x818..0x81F` flash range;
- a larger security/key-material-shaped record padded and programmed as `0x88` bytes.

The larger record's shape is consistent with the `0x84`-byte RSA modulus/exponent region plus flash
alignment, but the report does not publish the external DID or a write procedure. The per-record
flag table is not rendered cleanly enough to assert whether each special record requires SecurityAccess.

Defensive conclusion: protected-DID authorization is data-driven, and auditing the ROM descriptor
flags is necessary. The shared unlock bit is enforced only for records whose flag requests it.

## Download, program, and integrity boundaries

### RequestDownload

`FUN_00812554` checks request format/length, shared unlock, and current session 2 before starting an
asynchronous erase (`emira.c:16279-16332`). `FUN_00811824` then requires the requested start address
to exactly equal one of 15 descriptor bases. It records declared size, applies blank-only policy,
and dispatches the known erase worker (`emira.c:16036-16092`).

This is authorization plus capability restriction: unlock alone does not provide arbitrary address
selection.

### TransferData

`FUN_008127AC` repeats session and unlock checks for every admitted block, requires the expected
block counter, and limits total request length (`emira.c:16334-16380`). `FUN_00811B78` accumulates
CRC-16, buffers/alignment-corrects bytes, programs only through the selected descriptor, decrements
remaining declared length, rejects excess data, flushes the final buffer, and runs region
finalization (`emira.c:16094-16180`).

Thus a lost unlock/session transition terminates the programming state because the transition
clears both authorization and pending transfer fields.

### Transfer exit and authenticity

`FUN_00812980` requires session 2 and common unlock but only returns the accumulated CRC-16. It does
not perform RSA verification (`emira.c:16382-16430`). RSA/SHA verification is a separate routine and
bootability decision (`rsa_sign_check`, `firmware_integrity_validate`).

Transport CRC, flash-driver success, and application authenticity are therefore three distinct
trust boundaries:

1. transfer consistency (CRC and declared length);
2. descriptor-bounded successful programming;
3. header plus configured RSA/SHA boot authorization.

## Reset and session-timeout effects

ECUReset subfunction 1 is not gated by SecurityAccess (`FUN_00814B74`,
`emira.c:16938-16958`). Session inactivity in states 2/3 counts down from 5000. At zero,
`FUN_00815728` attempts the validation/reset path or reinitializes diagnostic state
(`emira.c:17205-17235`).

The unlock and seed-sequence state are cleared when a new session is committed; attempt/delay state
is not cleared by that function. A direct ECU reset also removes the RAM unlock, but the exact
relationship between reset cause and the boot-time security-delay query is unknown.

## Negative-response evidence

Security-relevant negative responses are used consistently:

| NRC | Observed security meaning |
|---:|---|
| `0x22` | requested key family does not match the family that issued the seed |
| `0x24` | key submitted without valid seed sequence / invalid transfer-exit state |
| `0x33` | protected programming/DID service attempted while common unlock is clear |
| `0x35` | incorrect key verification |
| `0x36` | attempt state exhausted |
| `0x37` | required delay still active |
| `0x72` | erase/program/finalization failure after authorization |
| `0x73` | TransferData block counter mismatch |
| `0x78` | authorized asynchronous operation still pending |
| `0x7E` | service not supported in current session |

Private codes `0xFB` and `0xFD` originate below the authorization layer for unknown descriptor base
and prohibited nonblank region respectively. They are region-policy failures, not SecurityAccess
results.

## Confirmed, inferred, and unresolved boundaries

### Confirmed

- externally reachable boot CAN/ISO-TP entry and dispatcher;
- programming session state 2 and 5000-count inactivity handling;
- two SecurityAccess families, seed-issued marker, common unlock bit, finite attempt state, and delay;
- both families grant the same visible post-unlock rights;
- per-block session/unlock checks for `0x34`, `0x36`, and `0x37`;
- exact descriptor-base restriction and per-region policy below RequestDownload;
- per-DID flag-driven SecurityAccess enforcement;
- absence of an explicit unlock check on visible RoutineControl erase admission;
- separation of transfer CRC, flash success, and RSA boot authenticity.

### Inferred

- sessions 1 and 3 correspond to default and extended;
- the larger special DID is RSA key-material provisioning;
- the boot-state query used at initialization provides cross-reset security-delay policy.

### Unknown

- exact timing units for retry delay, inactivity, and pending-response periods;
- persistence and power-cycle behavior of failed-attempt state;
- full contents and permission flags of the DID and memory-region descriptor tables;
- whether `0x7FF` reaches all state-changing services or is filtered by higher routing;
- whether the two SecurityAccess families are differentiated outside the visible boot dispatcher;
- effective externally reachable region set of RoutineControl erase;
- production provisioning state of RSA key material and any manufacturing-only transport controls;
- hardware flash protection and debug-port lifecycle settings.

## Defensive validation priorities

1. Decode the 12-entry DID table and 15-entry region table from the original binary, including every
   security, blank-only, read, write, and erase permission bit.
2. Confirm that RoutineControl erase requires an intended equivalent authorization control, or add
   the same common-unlock check used by RequestDownload.
3. Determine whether failed-attempt/delay state survives reset and power loss, then document timing
   in real units.
4. Verify whether the two SecurityAccess families are intentionally equivalent. If not, replace the
   shared boolean with explicit authorization levels and gate services accordingly.
5. Audit functional-ID routing so state-changing services cannot be initiated through unintended
   broadcast/secondary queues.
6. Validate key provisioning, RSA enforcement, verification-cache invalidation, hardware flash
   protection, and debug lifecycle as one combined boot trust model.
