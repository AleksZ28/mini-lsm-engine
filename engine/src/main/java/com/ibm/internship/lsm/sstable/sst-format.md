# SSTable binary format

All multibyte integers (int, long) are stored in standard Big-Endian byte order (Java default).

## File layout
    <beginning_of_file>
    [Data Section]
      ├── Record: [CRC32: 4B][OpType: 1B][KeyLen: 4B][ValLen: 4B][Key][Value]
      └── ...
    [Filter Block]
      ├── HashCount:    4 bytes - number of hash functions
      ├── NumBits:      4 bytes - total number of bits in the filter
      └── BitData:      raw bytes of the bitset array
    [Index Block]
      ├── Index Entry 1: [KeyLen: 4B][Key: UTF-8][Offset: 8B][RecordLen: 4B]
      └── ...
    [Footer] (Fixed 36 bytes at the end of the file)
      ├── FilterOffset: 8 bytes - file offset where Filter Block starts
      ├── FilterLength: 4 bytes - byte size of the Filter Block
      ├── IndexOffset:  8 bytes - file offset where Index Block starts
      ├── IndexLength:  4 bytes - byte size of the Index Block
      ├── EntryCount:   4 bytes - total number of keys in the SSTable
      ├── MagicNumber:  8 bytes - 0x4D494E494C534D31L ("MINILSM1")
    <end_of_file>

---

## 1. Data Section
Records are ordered lexicographically by key. Format is identical to the WAL record format:
- `CRC32` (4 bytes): Checksum of OpType + KeyLen + ValLen + Key + Value.
- `OpType` (1 byte): `0x01` (PUT) or `0x02` (DELETE / Tombstone).
- `Key Length` (4 bytes): Length of the key in bytes.
- `Value Length` (4 bytes): Length of the value in bytes (0 for Tombstone).
- `Key`: Key encoded in UTF-8.
- `Value`: Raw value bytes.

## 2. Filter Block (Bloom Filter)
Contains serialized probabilistic filter for fast key existence checks without disk I/O:
- `HashCount` (4 bytes): Number of hash functions.
- `NumBits` (4 bytes): Total number of bits in the filter.
- `BitData`: Serialized bits representation (bytes).

## 3. Index Block
Contains an index entry for each record:
- `Key Length` (4 bytes)
- `Key`: UTF-8 key
- `Offset` (8 bytes): Absolute file offset where the record begins in the Data Section.
- `Record Length` (4 bytes): Total size of the record.

## 4. Footer (Fixed 36 bytes)
Located at `fileSize - 36`:
- `FilterOffset` (8 bytes): File position where Bloom Filter starts.
- `FilterLength` (4 bytes): Size of the bloom filter block.
- `IndexOffset` (8 bytes): Where the Reader should seek to load the index.
- `IndexLength` (4 bytes): Size of the index block.
- `EntryCount` (4 bytes): Total count of entries.
- `MagicNumber` (8 bytes): Constant identifier validating file format (`0x4D494E494C534D31L`).