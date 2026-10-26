# WAL binary format

## Record layout

| Field | Type | Size | Description |
| :--- | :--- | :--- | :--- |
| `CRC32` | `uint32` | 4 bytes | Checksum of the remaining record bytes (OpType through Value) |
| `OpType` | `uint8` | 1 byte | Operation type flag: `0x01` = PUT, `0x02` = DELETE |
| `KeyLength` | `int32` | 4 bytes | Length of the key in bytes |
| `ValueLength` | `int32` | 4 bytes | Length of the value in bytes (must be `0` if `OpType == DELETE`) |
| `KeyBytes` | `byte[]` | Variable | Raw key payload (UTF-8 encoded) |
| `ValueBytes` | `byte[]` | Variable | Raw value payload |

### Header size: 13 bytes

## Error handling
During recovery, `WalReader` calculates the CRC32 of bytes from the start of `OpType` to the end of `ValueBytes`. If the calculated CRC does not match the stored `CRC32`, the record is treated as corrupted and reading stops.
