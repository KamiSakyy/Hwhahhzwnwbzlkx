"""Read-only APK size inventory. Never builds, installs or launches Android code."""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('apk', type=Path)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
groups = collections.defaultdict(lambda: {'compressed_bytes': 0, 'expanded_bytes': 0, 'entries': 0})
with zipfile.ZipFile(args.apk) as apk:
    entries = apk.infolist()
    for entry in entries:
        name = entry.filename
        category = 'dex' if name.endswith('.dex') else name.split('/')[0]
        if category not in ('dex', 'res', 'assets', 'lib', 'resources.arsc'):
            category = 'other'
        group = groups[category]
        group['compressed_bytes'] += entry.compress_size
        group['expanded_bytes'] += entry.file_size
        group['entries'] += 1
    largest = [{'path': entry.filename, 'compressed_bytes': entry.compress_size,
                'expanded_bytes': entry.file_size} for entry in
               sorted(entries, key=lambda entry: -entry.compress_size)[:20]]
report = {
    'apk': args.apk.as_posix(),
    'sha256': hashlib.sha256(args.apk.read_bytes()).hexdigest(),
    'file_bytes': args.apk.stat().st_size,
    'limit_bytes': 10000000,
    'meets_size_limit': args.apk.stat().st_size <= 10000000,
    'groups': dict(groups),
    'zip_headers_alignment_and_signing_bytes': args.apk.stat().st_size - sum(e.compress_size for e in entries),
    'largest_entries': largest,
    'limitations': ['Byte inventory only, not a runtime/visual test.',
                    'libtmessages combines UI/media/network functionality; its entire size is not attributed solely to protocol.'],
}
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
