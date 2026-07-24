# Emira G6 CAN and Diagnostics Analysis

Target: 2022 Lotus Emira V6 ROW firmware `8896915220A_ROW`, MPC5777C.

## Executive summary

The export contains two diagnostically important but distinct implementations:

1. The main application has a production OBD/DTC framework with a 59-entry Mode 01 PID table,
   lookup and response handlers, diagnostic sessions, freeze-frame-style data structures, and 258
   indexed DTC slots.
2. The bootloader program (code spanning at least `0x00800xxx..0x00820xxx`) has its own CAN transport and programming services,
   including security access and download/transfer state machines.

The main application also initializes a FlexCAN controller at `0xc3e60000` and has explicit receive
paths for application IDs `0x202` and `0x40..0x47`. The meanings of most vehicle frames are not yet
decoded. The current evidence is therefore sufficient to map architecture and diagnostic features,
but not to publish a complete Emira CAN signal database.

## CAN controllers and contexts

| Context | Evidence | Interpretation |
|---|---|---|
| Application FlexCAN at `0xc3e60000` | `FUN_00a06148()` calls `flexcan_init(&DAT_c3e60000)` (`emira.c:25147-25150`) | Main firmware CAN controller |
| Application RX `0x202` | `flexcan_c_rx_202()` validates the 11-bit arbitration ID and copies eight payload bytes (`emira.c:25429-25475`) | Confirmed application frame; signal meanings unknown |
| Application RX `0x40..0x47` | `flexcan_c_rx_40_41_42_43_44_45_46_47()` dispatches by ID and length (`emira.c:25482-25904`) | Confirmed low-ID command/data family; several cases move data through pointer-like payloads, so likely internal/test traffic rather than ordinary vehicle telemetry |
| Bootloader FlexCAN at `0xfffc0000` | Bootloader-region RX/TX globals and functions use `DAT_fffc...` (`emira.c:15722-15898`) | Programming/diagnostic transport; do not conflate with application bus behavior |

`flexcan_init()` programs 64 message-buffer slots, masks, bit timing, and interrupt enables
(`emira.c:66747-66888`). The literal `0x17a0007` is a register configuration word, but the clock
source is not proven in this export, so this note does not infer a bus bitrate from it.

## Main-application OBD framework

The global `obd_ii_handlers_mode01` contains 59 entries (`emira.c:8350`).
`obd_pid_lookup_routine___()` performs a binary search across indices 0 through 58
(`emira.c:83927-83957`). The surrounding code builds supported-PID bitmaps and dispatches a handler
through the function pointer stored in each table entry (`emira.c:85114-85230`).

Named handlers provide firm anchors:

| PID | Function | Evidence/result |
|---|---|---|
| `01 02` | `obd_ii_handler_mode01_0x02()` | Freeze-frame-related response path (`emira.c:84192`) |
| `01 05` | `obd_ii_handler_mode01_0x05()` | Temperature-style one-byte response (`emira.c:84267`) |
| `01 0C` | `obd_ii_handler_mode01_0x0C()` | Returns `obd_ii_engine_speed` big-endian (`emira.c:84434-84445`) |
| `01 11` | `obd_ii_handler_mode01_0x11()` | Returns `get_tps()` (`emira.c:84497-84510`) |
| `01 13` | `obd_ii_handler_mode01_0x13()` | Returns a calibration-coded oxygen-sensor-present byte (`emira.c:84516-84529`) |
| `01 43` | `obd_ii_handler_mode01_0x43()` | Absolute load; forced to zero with engine stopped (`emira.c:84603-84625`) |
| `01 44` | `obd_ii_handler_mode01_0x44()` | Commanded equivalence ratio/lambda, encoded at `2/65536` (`emira.c:84632-84653`) |
| `01 46` | `obd_ii_handler_mode01_0x46()` | Ambient-air-temperature-style one-byte response (`emira.c:84658`) |
| `01 63` | `obd_ii_handler_mode01_0x63()` | Two-byte engine reference torque selected by operating variant (`emira.c:84988-85010`) |
| `01 77` | `obd_ii_handler_mode01_0x77()` | Five-byte boost-control status response (`emira.c:84845-84861`) |

`FUN_00a88ca4()` builds one supported-PID bitmap from all 59 table entries, while
`FUN_00a88cf4()` builds a second availability bitmap subject to per-entry flags
(`emira.c:85086-85183`). This shows that compiled support and currently reportable support are
modeled separately.

## Diagnostic sessions and DTC model

`get_diag_session_state()` returns `DAT_4000b990` (`emira.c:78679-78684`). Numerous service
helpers require state `2`, demonstrating that extended/non-default access gates internal data and
commands. The numeric state-to-standard-session mapping is not yet proven and should not be named
without tracing the session-control dispatcher.

The DTC implementation is substantially larger than the current annotation set suggests:

- `dtc_queue_add_()` accepts indices below `0x102`, establishing 258 application DTC slots and a
  100-entry pending queue (`emira.c:79055-79082`).
- Initialization walks 258 records and clears per-DTC working data (`emira.c:79256-79337`).
- `dtc_set_status_()` updates indexed DTC state (`emira.c:83879`).
- The application builds diagnostic lookup metadata that combines ordinary DTC identifiers with
  OBD PID-backed identifiers (`emira.c:79192-79268`).
- Fixed-size records around `0x4001aabc` and `0x4001af00` are cleared and later enumerated by
  diagnostic service helpers (`emira.c:79295-79320`, `emira.c:77380-77620`). Their exact standard
  service mapping remains partially unnamed.

The framework clearly supports status, queued updates, snapshots/records, and session-gated reads.
It is not yet safe to label every unnamed routine as a specific SAE Mode or UDS service solely from
record shape.

## Bootloader programming diagnostics

The bootloader is a separate program with its own state and negative-response codes. The focused
`BOOTLOADER_PROGRAMMING_ANALYSIS.md` proves physical programming requests on CAN `0x730`, segmented
responses on `0x630`, and a secondary accepted request ID `0x7ff` whose functional role remains
inferred.
Confirmed behavior includes:

- Security-access subfunctions matching seed/key request pairs `0x01/0x02` and `0x11/0x12`, with
  negative responses such as `0x35`, `0x36`, and `0x37` (`emira.c:16197-16277` in
  `FUN_008120a4()`).
- A download/programming setup path that checks address/length/session/security state and returns
  response service `0x74` (`emira.c:16279-16332` in `FUN_00812554()`).
- A transfer-data state machine that writes chunks, advances a block counter, and returns service
  `0x76` (`emira.c:16334-16430` in `FUN_008127ac()`).
- CAN transmit helper `FUN_008109dc()` accepts standard IDs and up to eight payload bytes
  (`emira.c:15868-15898`).

These observations are consistent with UDS-style SecurityAccess (`0x27`), RequestDownload
(`0x34`), and TransferData (`0x36`) flows. The service identification is an inference from response
codes, subfunctions, and state-machine behavior; it should be confirmed against the actual request
dispatcher before producing a flashing protocol specification.

## What is not established

- The normal application diagnostic request/response arbitration IDs are not proven by the named
  application functions in this export.
- The meanings and scaling of application CAN IDs `0x202` and `0x40..0x47` are unresolved.
- No complete transmit-message list or periodic rate table has been recovered.
- The main application's actuator-test/service coverage has not yet been mapped to the level of the
  Evora Mode `0x2F` guide.
- Bootloader support does not imply that security access is available without the correct key or
  that a modified application will pass signature verification.

## Recommended next pass

1. Trace the bootloader CAN acceptance masks and request dispatcher to prove physical and
   functional IDs and all supported programming services.
2. Trace every main-application call to the `0xc3e60000` message buffers and build an ID/function/
   direction/rate inventory.
3. Decode `0x202` and `0x40..0x47` only with payload provenance or a matching vehicle capture.
4. Export the 59-entry PID table and 258-entry DTC metadata table from Ghidra data, preserving
   handler pointers and calibration gates.
5. Correlate captured Emira CAN traffic with runtime variables before assigning signal names or
   scaling.
