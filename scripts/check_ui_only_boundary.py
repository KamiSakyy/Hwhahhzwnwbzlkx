"""Source boundary guard; not compilation or proof of whole-UI visual identity."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1] / 'integration/ui-only'
allowed = {'android.graphics.Path', 'android.graphics.Rect', 'android.graphics.RectF'}
java_files = list((root / 'rendering/src/main/java').rglob('*.java'))
assert java_files, 'No extracted sources'
for path in java_files:
    text = path.read_text()
    imports = set(re.findall(r'^import\s+([^;]+);', text, flags=re.M))
    assert imports <= allowed, (path, imports - allowed)
    for forbidden in ('System.loadLibrary', 'native ', 'org.telegram.', 'java.net.', 'android.net.'):
        # Provenance comments may mention Telegram, but Java package references may not.
        code = re.sub(r'/\*.*?\*/|//[^\n]*', '', text, flags=re.S)
        assert forbidden not in code, (path, forbidden)
for path in root.rglob('*.gradle'):
    text = re.sub(r'//[^\n]*', '', path.read_text())
    assert not re.search(r'\b(implementation|api|compileOnly|runtimeOnly)\b', text), path
    assert 'TMessagesProj' not in text and 'externalNativeBuild' not in text, path
assert not list(root.rglob('*.so')) and not list(root.rglob('*.aar'))
manifest = ET.parse(root / 'rendering/src/main/AndroidManifest.xml').getroot()
assert len(manifest) == 0, 'UI library must not declare permissions, services or application initialization'
print(f'PASS: {len(java_files)} UI-only source(s), Android graphics imports only, no client/third-party/native dependencies.')
print('Source boundary only; not an Android build, runtime test or completed UI.')
