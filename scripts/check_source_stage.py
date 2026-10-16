"""Source-only guard. Does not run Gradle, Android tools or produce an APK."""
import pathlib
import subprocess

root = pathlib.Path(__file__).resolve().parents[1]
for filename, job in [('android-release.yml', 'release'), ('handoff.yml', 'handoff')]:
    text = (root / '.github/workflows' / filename).read_text()
    assert '\n  push:' not in text, filename
    assert '\n    if: ${{ false }}\n' in text, filename
sha = subprocess.check_output(['git', '-C', str(root / 'vendor/telegram'), 'rev-parse', 'HEAD'], text=True).strip()
assert sha == 'f32ffeec763f0bdbf4bec25b52d654f77bff733d', sha
for source in ['ThemePreviewActivity.java', 'Cells/ChatMessageCell.java', 'Cells/DialogCell.java', 'Components/ThemeEditorView.java', 'ActionBar/Theme.java']:
    path = root / 'vendor/telegram/TMessagesProj/src/main/java/org/telegram/ui' / source
    assert path.is_file(), path
bridge = (root / 'integration/telegram/src/main/java/dev/themestudio/telegram/EditorThemePreviewActivity.java').read_text()
assert 'extends ThemePreviewActivity' in bridge
assert 'return super.createView(context);' in bridge
assert 'drawText(' not in bridge and 'drawRoundRect(' not in bridge
print('Source guards OK: official checkout pinned; APK jobs blocked; adapter delegates rendering to upstream.')
print('This is NOT a compile, runtime, visual or completion test.')
