# Director Multiuser Transport

Director's Multiuser Xtra can be used with the original SMUS packet framing,
but legacy movies may also build an application protocol in Lingo and send raw
payloads through the message content field. The transport layer must keep that
wire-format decision per connection instead of hardcoding it in a specific
player bridge.

Rules used by the emulator:

- non-zero connect mode is content-only
- SMUS mode encodes outgoing normal messages as SMUS packets
- sender/subject `0`/`0` is a content-only envelope
- incoming SMUS streams may arrive split across host callbacks and must buffer
  incomplete SMUS prefixes
- if a nominal SMUS stream starts with bytes that cannot be SMUS framing, the
  connection switches to content-only and delivers the buffered raw bytes

That last rule is important for compatibility with movies that instantiate a
Multiuser connection but speak their own text/binary protocol after connection.
It is not a host-specific workaround: both desktop sockets and WASM/WebSocket
bridges use the same `MultiuserTransportState`.
