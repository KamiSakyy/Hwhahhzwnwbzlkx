"""Read-only import graph audit. No Java execution, Gradle, Android tools or device."""
import collections
import json
import pathlib
import re
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]
UPSTREAM = ROOT / 'vendor/telegram'
JAVA = UPSTREAM / 'TMessagesProj/src/main/java'
roots = [
    'org.telegram.ui.ThemePreviewActivity',
    'org.telegram.ui.Cells.ChatMessageCell',
    'org.telegram.ui.Cells.DialogCell',
    'org.telegram.ui.ActionBar.Theme',
    'org.telegram.messenger.ApplicationLoader',
]
files = {}
imports = {}
for path in JAVA.rglob('*.java'):
    text = path.read_text(errors='replace')
    package = re.search(r'^package\s+([\w.]+)\s*;', text, re.M)
    if package:
        name = package[1] + '.' + path.stem
        files[name] = path
        imports[name] = re.findall(r'^import\s+(?:static\s+)?([\w.*]+)\s*;', text, re.M)

def resolve(name):
    while '.' in name:
        if name in files:
            return name
        name = name.rsplit('.', 1)[0]
    return None

queue = collections.deque(roots)
visited, missing, external = set(), set(), set()
direct = {}
while queue:
    name = queue.popleft()
    if name in visited:
        continue
    if name not in files:
        missing.add(name)
        continue
    visited.add(name)
    edges = set()
    for imported in imports[name]:
        target = resolve(imported)
        if target:
            edges.add(target)
            queue.append(target)
        elif imported.startswith('org.telegram.'):
            missing.add(imported)
        elif not imported.startswith(('java.', 'javax.', 'android.')):
            external.add(imported)
    if name in roots:
        direct[name] = sorted(edges)

version = re.search(r'^APP_VERSION_NAME=(.*)$', (UPSTREAM / 'gradle.properties').read_text(), re.M)[1]
report = {
    'upstream_commit': subprocess.check_output(['git', '-C', str(UPSTREAM), 'rev-parse', 'HEAD'], text=True).strip(),
    'upstream_version_property': version,
    'method': 'Lexical explicit-import closure. This is a LOWER BOUND, not a complete Java dependency analysis.',
    'limitations': ['No same-package implicit references', 'No reflection/runtime/native dependency resolution', 'No compiled bytecode/resource/APK size estimate', 'Generated sources and nested wildcard imports may appear unresolved'],
    'java_source_files_indexed': len(files),
    'reachable_source_files_lower_bound': len(visited),
    'reachable_source_bytes_not_apk_size': sum(files[n].stat().st_size for n in visited),
    'unresolved_telegram_import_count': len(missing),
    'unresolved_telegram_imports': sorted(missing),
    'external_import_count': len(external),
    'external_import_examples': sorted(external)[:40],
    'roots_and_direct_source_imports': direct,
    'packaging_performed': False,
    'emulator_used': False,
}
print(json.dumps(report, ensure_ascii=False, indent=2))
