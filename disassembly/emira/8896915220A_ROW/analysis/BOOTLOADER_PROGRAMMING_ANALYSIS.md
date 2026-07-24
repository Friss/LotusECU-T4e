# Emira G6 bootloader and programming analysis

Target: 2022 Lotus Emira V6 ROW ECU firmware `8896915220A_ROW`, MPC5777C/G6.

This pass validates the historical `BOOTLOADER_ANALYSIS.md` against the bootloader bodies in `emira.c`. It focuses on the diagnostic programming path and deliberately distinguishes confirmed behavior, strong protocol inference, and unresolved details.

## Executive findings

Confirmed:

- Bootloader code spans at least `0x00800000..0x00820xxx`; it is not confined to the historical `0x00810000..0x0081FFFF` claim.
- The boot diagnostic dispatcher supports services `0x10`, `0x11`, `0x22`, `0x27`, `0x28`, `0x2E`, `0x31`, `0x34`, `0x36`, `0x37`, `0x3E`, and vendor service `0x85`.
- CAN transport accepts 11-bit IDs `0x730` and `0x7FF` and transmits segmented responses on `0x630`.
- Programming requires numeric session state `2` and successful SecurityAccess.
- SecurityAccess supports seed/key pairs `01/02` and `11/12`, using a non-zero 32-bit seed and a derived 24-bit key.
- RequestDownload erases a table-selected region asynchronously, TransferData programs it while accumulating CRC-16, and RequestTransferExit returns that CRC.
- Application authenticity uses RSA-1024/SHA-1 when a public key is provisioned. A blank key explicitly makes `rsa_sign_check()` succeed.
- The main image must also have an in-range header pointer and ASCII words `FLASHEND` before it is considered bootable.

Not confirmed:

- The decompile does not expose a direct `branch 0x00A00100` in `main_boot`; application hand-off occurs through reset/startup state after `firmware_integrity_validate()` succeeds.
- The 128-byte firmware signature's absolute flash address is not preserved by the exported symbol. The historical `0x00F00004` assertion is therefore unsupported.
- `0x730` is clearly the primary physical programming request ID and `0x7FF` an accepted secondary request ID; calling the latter “functional broadcast” is plausible but remains inference.
- The region descriptor table at `0x00820D24` is not decoded into a complete address/length/permission list in this export.

## Corrections to the historical note

| Historical claim | Current assessment |
|---|---|
| Bootloader is `0x00810000..0x0081FFFF` | Incorrect/incomplete. Boot functions start in the `0x00800xxx` range, `main_boot` is at `0x00809CD4`, service code occupies `0x00812xxx`, crypto reaches `0x0081Dxxx`, and tables extend beyond `0x00820D00`. |
| Bootloader starts at `0x00810000` | Not established. `FUN_00810000` is a CAN TX continuation, not reset entry. The exported startup pointer is `PTR_init_0082076c`; `main_boot` is the recovered high-level boot main. |
| Bootloader always verifies a certificate then jumps directly to `0x00A00100` | Oversimplified. Boot mode is checked first; diagnostic programming may run. Bootability combines header bounds/magic and `rsa_sign_check()`, then a reset/startup path performs hand-off. |
| RSA public key is at `0x00000900`, exponent at `0x00000980` | Confirmed by symbols and use. The key area is `0x84` bytes: 128-byte modulus plus four-byte exponent. |
| Encrypted signature is at `0x00F00004` | Unsupported. `signcheck()` reads `firmware_signature_`, but this export lost its absolute address. `0x00F00000..0x00F00087` is a separate marker/verification structure with `0x55AA55AA` sentinels. |
| Signature hashes calibration, header, and main code | Confirmed, with a nuance: two candidate SHA-1 hashes are formed, differing only in an eight-byte all-zero versus all-`FF` window after the first `0xA0` application-header bytes. |
| RSA verification always prevents unsigned firmware | Incorrect as an absolute statement. `rsa_sign_check()` returns success when the public-key region is blank; when the key exists it performs or reuses a cached verification. |
| “Certificate” at `0x00090000` | Incorrect address scale. The public key is at `0x00000900`, not `0x00090000`. The firmware signature is a separate 128-byte value of unresolved address. |

## Boot entry, mode decision, and application hand-off

`main_boot` is at `0x00809CD4` (`emira.c:13859`). It performs low-level initialization through `FUN_0081e1e8`, sets boot-state bit `0x10`, and tests `(DAT_40000A90 & 0x18) != 0` through `FUN_008051e4` (`emira.c:12129-12134`). While that condition remains true, `FUN_00805d10` services the boot protocol, CAN TX, timers, and its proprietary boot state machine (`emira.c:12361-12375`).

After leaving that early boot-mode loop, `main_boot` initializes the UDS/ISO-TP path with `FUN_0081e234` and then remains in a perpetual service loop (`FUN_0080fa2c` plus watchdog servicing). Application start is therefore not a fall-through call from `main_boot`.

The recovered hand-off decision is distributed:

1. `firmware_integrity_validate()` (`0x00809A44`, `emira.c:13779`) requires the application header pointer to be greater than `0x009FFFFF` and less than `0x00C7FFF9`.
2. The first two header words must be `0x464C4153` and `0x48454E44`, spelling `FLASHEND` in big-endian word order.
3. `rsa_sign_check()` must succeed.
4. Session/reset code calls `FUN_0080e51c` (the validator) and then `FUN_0080e440`, which stores reset/boot state and invokes the reset path (`FUN_0080e414` -> `FUN_00809cd4`).

The project note identifies application `init` at `0x00A00100`. That target is consistent with the application export, but the final reset-vector decode that selects it is still missing. The safe statement is “validated reset hand-off to the application image,” not “`main_boot` directly calls `0x00A00100`.”

Failure behavior is fail-closed when a key is provisioned: invalid header bounds/magic or failed signature prevents the validator from authorizing the application-reset path. The boot protocol remains available, with timeout/reset behavior governed by session state. Exact power-on retry timing is not fully reconstructed.

## CAN and ISO-TP transport

The bootloader uses FlexCAN registers rooted at `0xFFFC0000`. Both RX mailbox handlers extract the 11-bit arbitration ID and deliver frames to `flexcan_process_730_7ff` (`emira.c:15722-15807`). That function accepts only eight-byte frames on:

- `0x730`: queued in the large primary RX ring;
- `0x7FF`: queued in a small secondary RX ring.

This is direct evidence for accepted request IDs (`flexcan_process_730_7ff`, `0x008171E8`, `emira.c:17858-17911`). The ISO-TP implementation recognizes single, first, consecutive, and flow-control PCI nibbles, validates sequence numbers, supports payloads up to `0x1FF` bytes, and uses 1000-tick transport timeouts (`emira.c:17309-17798`).

All segmented diagnostic response helpers call `FUN_008109DC(0x630, 8, ...)` (`emira.c:17338`, `17376`, `17424`, `17481`). Thus `0x630` is the confirmed boot response ID. `0x730 -> 0x630` is the physical pair. `0x7FF` is accepted by a separate short queue and is likely functional/broadcast traffic, but the export does not prove its tester semantics.

The separate early boot/proprietary transport also uses a CRC-8 over fixed CAN records. `FUN_00805224` uses the 256-byte table at `0x0081E778`; the verified table corresponds to polynomial `0x31`, initial value zero (`emira.c:12136-12155`). That record CRC must not be confused with ISO-TP framing or the TransferData CRC-16.

## Diagnostic sessions and supported services

The central dispatcher is `FUN_00814D84` at `0x00814D84` (`emira.c:17009-17103`). Supported services are:

| Request | Positive response | Handler | Confirmed role |
|---:|---:|---|---|
| `0x10` | `0x50` | `FUN_008140B8` | DiagnosticSessionControl |
| `0x11` | `0x51` | `FUN_00814B74` | ECUReset |
| `0x22` | `0x62` | `FUN_00813D54` | ReadDataByIdentifier |
| `0x27` | `0x67` | `FUN_008120A4` | SecurityAccess |
| `0x28` | `0x68` | `FUN_0081496C` | CommunicationControl-like behavior |
| `0x2E` | `0x6E` | `FUN_008138A4` | WriteDataByIdentifier |
| `0x31` | `0x71` | `FUN_00814380` | RoutineControl |
| `0x34` | `0x74` | `FUN_00812554` | RequestDownload |
| `0x36` | `0x76` | `FUN_008127AC` | TransferData |
| `0x37` | `0x77` | `FUN_00812980` | RequestTransferExit |
| `0x3E` | `0x7E` | `FUN_008142AC` | TesterPresent |
| `0x85` | `0xC5` | `FUN_00814AA0` | ControlDTCSetting |

Unknown services receive NRC `0x11`. Some requests with the suppress-positive-response bit set cause the response to be discarded after successful execution, matching UDS bit-7 behavior.

`DiagnosticSessionControl` accepts subfunctions `1`, `2`, and `3`. Session state `2` is definitively the programming session because RequestDownload, TransferData, security, and write operations require it. Session `1` behaves as default and session `3` as extended; those names are standard-consistent inference supported by timeout setup in `FUN_00814C48` and `FUN_00815574` (`emira.c:16978-17006`, `17155-17170`). Session changes clear security-unlocked state, transfer counters, pending asynchronous operations, and transport/programming state.

Programming/extended sessions arm a 5000-tick inactivity timeout. On expiry, `FUN_00815728` either validates and resets toward application execution or returns the protocol to a lower session (`emira.c:17205-17235`).

## SecurityAccess seed/key

`FUN_008120A4` (`0x008120A4`, `emira.c:16197-16277`) supports two levels:

- request seed `0x01`, send key `0x02`;
- request seed `0x11`, send key `0x12`.

Both require session state `2`. `FUN_00819C68` obtains a non-zero 32-bit value from `FUN_00800940`, returns its low 24 bits as the three-byte seed, and computes the expected three-byte key with `FUN_0081938C` (`emira.c:18535-18601`). The two levels select different internal state but the decompiler dropped several arguments at the call site, so it is not safe to assert that both levels have identical key results for a given seed.

The key transform is a 64-round, 24-bit LFSR-style algorithm initialized with `0xC541A9`; its feedback masks are explicit in `FUN_0081938C`. This validates `boot_crypto_names.txt`. It is not RSA and is independent of firmware signature verification.

Security state behavior:

- already unlocked returns a zero seed;
- a correct three-byte key sets `DAT_400018DE = 1` and returns positive `0x67`;
- incorrect sequence/level returns `0x22` or `0x24` depending state;
- invalid key returns `0x35` and starts/decrements attempt state;
- exhausted attempts return `0x36` and set a delay counter;
- a request made during delay returns `0x37`.

The attempt counter is initialized to two by `FUN_0081560C`; an invalid attempt can also load a ten-tick delay. The exact wall-clock duration of those ticks is not proved here.

## Download, erase, transfer, and exit

### RequestDownload (`0x34`)

`FUN_00812554` requires exactly 11 request bytes, a format beginning `00 44`, unlocked security, and session state `2` (`emira.c:16279-16332`). The remaining eight bytes are decoded as a big-endian four-byte address and four-byte size. On acceptance it sends NRC `0x78` while the asynchronous erase/setup worker runs.

`FUN_00811824` (`boot_download_erase`, `0x00811824`, `emira.c:16036-16092`) requires the requested start address to exactly match one of 15 entries in the descriptor table at `0x00820D28`. It can require the selected region to be blank before proceeding, records the requested byte count, and dispatches erase through `FUN_00819CD8`. Unknown start addresses produce private NRC `0xFB`; nonblank protected/one-time regions produce `0xFD`; erase failure maps to `0x10` or `0x72` depending stage.

On success the response bytes are `74 20 01 F2`. This advertises a `0x1F2` maximum transfer block length under the firmware's interpretation. The next expected TransferData sequence counter is initialized to `1`.

### TransferData (`0x36`)

`FUN_008127AC` accepts total request lengths from 2 through `0x1F2`, requires unlocked programming session, and requires the request block sequence counter to equal the expected counter (`emira.c:16334-16380`). A mismatch returns NRC `0x73`.

`FUN_00811B78` (`boot_download_program`, `0x00811B78`, `emira.c:16094-16180`) performs the data pump:

1. excludes SID and block counter from the programmed payload;
2. updates running CRC-16 `DAT_40001CE6`, initialized to `0xFFFF` by `FUN_008117DC`;
3. queues bytes into an aligned programming buffer;
4. writes through `FUN_00819F30`/`FUN_00809324` or copies into a permitted RAM shadow;
5. tracks remaining requested download length;
6. performs a final zero-length flush and selected-region verification/finalization.

Successful blocks return `76 <blockCounter>` and increment the counter modulo 256. Too much received data returns NRC `0x71`; flash/program failure returns `0x72`.

### RequestTransferExit (`0x37`)

`FUN_00812980` requires a one-byte request in unlocked programming session. It responds with `77` followed by the two-byte running CRC-16 (`emira.c:16400-16430`). The requester does not supply an expected CRC in this service; the ECU reports its calculated value for tester comparison.

This corrects a common shorthand: `0x37` does not itself perform RSA verification. Region finalization may invoke additional checks during the TransferData worker, while RSA/SHA verification is exposed separately through RoutineControl and bootability validation.

## Region permissions and flash dispatch

The RequestDownload descriptor table contains 15 exact base/length/type records at `0x00820D24..`. Although the raw table values are not rendered cleanly, erase/program dispatch proves support for these classes:

| Base/selector | Observed role | Constraints/evidence |
|---:|---|---|
| `0x00000000` | combined/special programming operation | erase dispatcher sequences calibration then application verification |
| `0x00000200`-area selectors (`0x800`, `0x810`, `0x818`, `0x820`, `0x840`, `0x860`, `0x880`, `0x900`) | boot metadata, identifiers, key material | fixed lengths and often blank-only/one-time write policy in the older block protocol; erase routes to metadata driver |
| `0x00020000` | 64 KiB calibration | explicit erase/verify dispatch; signature hash covers it |
| application base from header, normally `0x00A00000` | primary application | maximum historical request length `< 0x280001`; erase spans multiple flash blocks |
| `0x00C80000` | secondary large application/data region | maximum historical request length `< 0x280001` |
| `0x00F00000` | 256 KiB region / verification marker area | maximum historical request length `< 0x40001`; erase clears verification cache |

Addresses below `0x01000000` are programmed with the flash driver. `FUN_00819F30` treats a narrowly bounded RAM range around `0x4000795C..0x40007A83` as memcpy-backed shadow data and rejects other out-of-range destinations (`emira.c:18647-18673`). Requests cannot select arbitrary addresses: start address must match a descriptor, and programming length is bounded by that descriptor and the RequestDownload length.

The legacy/proprietary boot record path additionally exposes selectors for learned `0x10000` and coding `0x30000`, but those bases are not present in `FUN_00819CD8`'s UDS erase dispatcher. Therefore this report does not claim that UDS RequestDownload can erase those two regions without decoding the descriptor table.

## RSA/SHA authenticity decision

### Confirmed cryptographic construction

`signcheck()` (`emira.c:19869-19908`) computes two SHA-1 digests over:

```text
calibration:  0x00020000, length 0x10000 (`CALROM`)
header:       application-header pointer, length 0xA0
variant gap:  eight bytes of all 00 (digest 1) or all FF (digest 2)
application:  header pointer + 0xA8, length 0x27FF58
```

It reverses the 128-byte modulus at `0x00000900`, reverses the four-byte exponent at `0x00000980`, reverses the 128-byte firmware signature, and performs RSA modular exponentiation with `decrypt_cert_`. It then compares the final 20 bytes of the 128-byte decoded block (`certout + 108`) with either digest. This is RSA-1024 with SHA-1 and a firmware-specific decoded-block check; the code does not validate a full ASN.1/PKCS#1 structure in the visible comparison.

### Decision policy

`rsa_key_flash_check()` treats the full `0x84`-byte key/exponent area as unprovisioned when it is all `0xFF`. `rsa_sign_check()` then returns success without hashing or RSA (`emira.c:19954-20017`). If a key exists, it first checks an eight-byte cached verification token; only a cache miss performs `signcheck()`. A successful signature caches that token.

The cache token is invalidated by application/calibration erase paths through `verification_result_clear()`. RoutineControl ID `0x0205` invokes `rsa_sign_check()` and returns a structured result; ID `0x0200` invokes a fuller status check covering key provisioning, the `0x00F00000` sentinel structure, and signature result (`FUN_00814380`, `emira.c:16799-16890`).

The structure at `0x00F00000` is valid only if 0x88 bytes are nonblank and words at offsets `0` and `0x84` equal `0x55AA55AA` (`FUN_0081DD5C`, `emira.c:19966-19983`). It is not the 128-byte RSA firmware signature asserted by the old report.

## Negative responses and asynchronous behavior

Confirmed NRCs used by the dispatcher and programming handlers:

| NRC | Standard meaning | Observed use |
|---:|---|---|
| `0x10` | general reject | erase/setup driver failure |
| `0x11` | service not supported | unknown SID |
| `0x12` | subfunction not supported | invalid session/security/reset subfunction |
| `0x13` | incorrect message length/format | length mismatch |
| `0x22` | conditions not correct | wrong security sequence/level state |
| `0x24` | request sequence error | missing seed/invalid service state |
| `0x31` | request out of range | unsupported DID/routine/format |
| `0x33` | security access denied | programming service while locked |
| `0x35` | invalid key | SecurityAccess mismatch |
| `0x36` | exceeded number of attempts | seed request after exhausted attempts |
| `0x37` | required time delay not expired | SecurityAccess delay active |
| `0x71` | transfer data suspended | more data than declared/download-state failure |
| `0x72` | general programming failure | erase/program/finalization failure |
| `0x73` | wrong block sequence counter | TransferData counter mismatch |
| `0x78` | response pending | asynchronous erase, program, routine, or session action |
| `0x7E` | service not supported in active session | wrong diagnostic session |
| `0x7F` | decompiler-visible private/internal fallback | several handler state failures before outer NRC framing |
| `0xFB`, `0xFD` | private bootloader codes | unknown region descriptor / prohibited nonblank region |

The dispatcher preserves a pending request across calls when NRC `0x78` is set. It rate-limits repeated pending responses using a timer threshold of `0x9C4` ticks (`FUN_00814D84`, `emira.c:17079-17103`). Erase, program, signature verification, and some routine controls are state machines returning `2` while busy, `1` on success, and `0` on failure.

On transport timeout or malformed ISO-TP sequencing, the message is discarded and the request state resets. On programming failure, the region may have been erased or partially written; the verification cache is cleared for authenticity-covered regions, preventing a stale successful validation from authorizing the damaged image.

## Confirmed, inferred, and unknown summary

### Confirmed

- Physical programming request `0x730`, response `0x630`, secondary accepted request `0x7FF`.
- UDS-style dispatcher and exact supported SIDs listed above.
- Programming session state `2`, security unlock gate, `01/02` and `11/12` seed/key levels.
- Exact Download/Transfer/Exit response services, block sequencing, max block response, and reported CRC-16.
- Descriptor-selected addressing, asynchronous erase, aligned program buffering, verification-cache invalidation.
- RSA-1024/SHA-1 hashes and dual 00/FF gap variants.
- Header bounds and `FLASHEND` magic in the application bootability check.

### Strong inference

- Session values `1/2/3` correspond to default/programming/extended.
- `0x7FF` is a functional/broadcast request ID.
- Reset state ultimately selects application entry `0x00A00100` after successful validation.

### Unknown

- Absolute address of `firmware_signature_`.
- Complete decoded contents of the 15-entry UDS memory descriptor table.
- Exact tester timing units for security delay, P2/P2*, session timeout, and pending-response cadence.
- Hardware flash protection/watchdog configuration during each block operation.
- Whether any manufacturing state intentionally ships with an all-`FF` key region, and therefore with signature enforcement disabled.

## Practical implications

The bootloader is substantially more capable and more conditional than the historical summary suggested. A valid reflash is a stateful sequence: enter programming session, unlock a security level, request a descriptor-approved region/size, wait through erase, transfer strictly numbered blocks no larger than the advertised limit, compare the returned CRC-16, invoke the relevant verification routine, then reset. A successful transport CRC does not imply a bootable image; header structure and RSA/SHA policy are separate gates. Conversely, RSA enforcement is conditional on public-key provisioning, an important fact for forensic interpretation but not evidence that a particular production ECU has a blank key.
