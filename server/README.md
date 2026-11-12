# Optional Echo sync receiver

This server is not needed for local recording. It adapts the trace-ingestion behavior in copa-sh/echo, replacing URL credentials and delete-on-2xx semantics with a versioned checksum acknowledgement. GPL-3.0-or-later.

Run `go test ./...` and `go build .`. Set `ECHO_SYNC_TOKEN` to a random token of at least 32 characters in your secure runtime environment. Never put it in Git or a URL. Set `ECHO_SYNC_DIRECTORY` to durable storage. The default listener is `127.0.0.1:8080`; expose it through an HTTPS reverse proxy. The Android client accepts HTTPS only.

`PUT /v1/traces/<sha256>` uses `Authorization: Bearer <token>`, streams at most 128 MiB into a private temporary file, checks the body checksum/length, syncs the file, atomically renames it, syncs the containing directory, then replies with `{"sha256":"...","bytes":123,"durable":true}`. Repeating identical content is idempotent. Blob filenames are content hashes. Upload original-name/clip metadata separately as JSON when reconstruction matters. No credentials or file contents are logged.

The client retains local originals. Acknowledgement means this server completed filesystem sync; backups and the durability of the underlying filesystem remain the server operator's responsibility. This server does not provide a web UI, user accounts, remote deletion, or server-side retention.

The Android client uploads a reconstruction manifest after each blob, preserving original names, sizes, timestamps and MIME types. Reconstruct into a separate destination using Python 3.11 or newer:

```sh
python3 restore.py /path/to/blob-directory /path/to/restored-files
```

The utility verifies SHA-256, rejects unsafe names and retains differing existing files. Repeated restoration is idempotent. The client reports files exceeding 128 MiB as skipped; split or share them separately. Server files are not automatically rotated or deleted.
