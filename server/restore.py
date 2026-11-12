"""Reconstruct acknowledged Echo manifests without overwriting different local content."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil


def restore(blobs: Path, destination: Path) -> int:
    destination.mkdir(parents=True, exist_ok=True)
    restored = 0
    for manifest_path in blobs.glob('*.blob'):
        if manifest_path.stat().st_size > 65536:
            continue
        try:
            manifest = json.loads(manifest_path.read_text())
        except (ValueError, UnicodeError):
            continue
        if not isinstance(manifest, dict) or manifest.get('type') != 'echo_manifest_v1':
            continue
        name, digest = manifest.get('name'), manifest.get('sha256')
        if not isinstance(name, str) or name in ('', '.', '..') or '/' in name or '\\' in name:
            raise ValueError('Invalid filename in manifest')
        if not isinstance(digest, str) or len(digest) != 64 or any(c not in '0123456789abcdef' for c in digest):
            raise ValueError('Invalid checksum in manifest')
        source = blobs / (digest + '.blob')
        if not source.is_file():
            raise ValueError('Missing acknowledged blob')
        with source.open('rb') as content:
            if hashlib.file_digest(content, 'sha256').hexdigest() != digest:
                raise ValueError('Corrupt acknowledged blob')
        target = destination / name
        if target.exists():
            with target.open('rb') as existing:
                if hashlib.file_digest(existing, 'sha256').hexdigest() == digest:
                    continue
            target = destination / (Path(name).stem + '-' + digest[:12] + Path(name).suffix)
        # Exclusive create protects existing destinations, including concurrent restores.
        try:
            with target.open('xb') as output, source.open('rb') as input_file:
                shutil.copyfileobj(input_file, output)
        except FileExistsError:
            continue
        restored += 1
    return restored


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('blobs', type=Path)
    parser.add_argument('destination', type=Path)
    args = parser.parse_args()
    print(f'Restored {restore(args.blobs, args.destination)} files')
