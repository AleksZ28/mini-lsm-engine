# Mini LSM Engine
*IBM Internship Project*

---

A lightweight key-value storage engine built in Java 21 based on the Log-Structured Merge-tree (LSM) architecture. Includes an HTTP API and a minimal web dashboard.

## Features

- **MemTable**: In-memory buffer for fast writes.
- **Write-Ahead Log (WAL)**: Append-only disk log for crash recovery.
- **SSTables**: Immutable, sorted on-disk files with index blocks.
- **Bloom Filter**: Probabilistic filter to skip unnecessary SSTable disk reads.
- **Compaction**: Merge iterator to combine SSTables and purge tombstones.
- **Web UI & API**: Simple dashboard and REST endpoints powered by Javalin.

## Running with Docker

Make sure Docker is running, then execute:

```bash
docker compose up --build
```

- Dashboard: http://localhost:7070
- Database files (`log.wal`, `*.sst` files) are stored locally in the `./data` directory so data persists between restarts.

To stop:
```bash
docker compose down
```

> Running locally requires Java 21.


## API

- `POST /api/entries` - Add or update a key-value pair (`{"key": "x", "value": "y"}`)
- `GET /api/entries/{key}` - Get value by key
- `DELETE /api/entries/{key}` - Delete a key
- `POST /api/flush` - Trigger manual flush from MemTable to SSTable
- `GET /api/stats` - Get engine stats