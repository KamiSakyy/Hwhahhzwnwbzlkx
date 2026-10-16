"""Read-only verification of the complete pinned upstream worktree. No Android tools."""
import json
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
upstream = root / 'vendor/telegram'
manifest = json.loads((root / 'docs/TELEGRAM_CHECKOUT.json').read_text())

def git(*args):
    return subprocess.check_output(['git', '-C', str(upstream), *args], text=True).strip()

assert git('rev-parse', 'HEAD') == manifest['upstream_commit'], 'Wrong upstream revision'
sparse = subprocess.run(['git', '-C', str(upstream), 'config', '--bool', 'core.sparseCheckout'], text=True, capture_output=True).stdout.strip()
assert sparse != 'true', 'Sparse checkout is still enabled'
# list-index entries under ordinary trees only: every indexed upstream file must be present.
missing = []
for entry in subprocess.check_output(['git', '-C', str(upstream), 'ls-files', '--stage', '-z']).split(b'\0'):
    if not entry:
        continue
    metadata, raw_path = entry.split(b'\t', 1)
    mode = metadata.split()[0]
    path = upstream / raw_path.decode()
    if mode != b'160000' and not (path.exists() or path.is_symlink()):
        missing.append(str(path.relative_to(upstream)))
assert not missing, 'Missing upstream files: ' + ', '.join(missing[:10])
actual = {}
for line in subprocess.check_output(['git', '-C', str(upstream), 'submodule', 'status', '--recursive'], text=True).splitlines():
    assert line.startswith(' '), 'Incomplete/mismatched submodule: ' + line
    sha, path, *_ = line[1:].split()
    actual[path] = sha
expected = {s['path']: s['sha'] for s in manifest['submodules']}
assert actual == expected, 'Submodule list or commit differs from upstream lock'
assert not git('status', '--porcelain'), 'Official source worktree was modified'
print(f'Full original worktree verified; {len(actual)} submodules exactly match pinned SHAs.')
print('No Gradle, SDK, APK, emulator or device execution was performed.')
