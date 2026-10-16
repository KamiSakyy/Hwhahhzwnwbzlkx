"""Read-only design integration guard. Not a visual/runtime test; no Android tools."""
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
java = root / 'integration/telegram/src/main/java/dev/themestudio/telegram'
bridge = (java / 'EditorThemePreviewActivity.java').read_text()
assert 'View officialView = super.createView(context);' in bridge
assert 'extends ThemePreviewActivity' in bridge
assert 'drawText(' not in bridge and 'drawRoundRect(' not in bridge
chrome = (java / 'EditorMenuController.java').read_text()
assert 'setActionBarMenuOnItemClick' not in chrome
assert 'addItem(' not in chrome
layer = (java / 'EditorTouchLayer.java').read_text()
assert 'onDraw(' not in layer and 'dispatchDraw(' not in layer
assert 'if (listener == null) return super.dispatchTouchEvent(event);' in layer
picker = (java / 'ColorEditorController.java').read_text()
assert 'new BottomSheet.Builder' in picker and 'new ColorPicker(' in picker
assert 'new AlertDialog.Builder' not in picker
assert 'setDimBehind(false)' in picker
ns = '{http://schemas.android.com/apk/res/android}'
layout = ET.parse(root / 'integration/android-host/src/main/res/layout/activity_editor.xml').getroot()
preview = next(node for node in layout if node.get(ns+'id') == '@+id/official_preview_container')
assert preview.tag == 'FrameLayout' and len(preview) == 0
assert preview.get(ns+'layout_width') == 'match_parent'
assert layout.tag == 'FrameLayout', 'Editor tools must not shrink the official viewport'
assert preview.get(ns+'layout_height') == 'match_parent'
assert preview.get(ns+'layout_weight') is None
assert preview.get(ns+'layout_marginBottom') is None
assert preview.get(ns+'paddingBottom') is None
tools = next(node for node in layout if node.get(ns+'id') == '@+id/editor_tools')
assert tools.get(ns+'layout_gravity') == 'bottom'
assert any(node.get(ns+'id') == '@+id/editor_tools' for node in layout)
for node in layout.iter():
    assert node.get(ns+'layout_width') != '360dp', 'No fixed/scaled phone canvas allowed'
print('Design source guards OK: original preview creation, untouched ActionBars, separate editor chrome, original Telegram sheet/picker.')
print('This does NOT establish pixel identity, runtime correctness or completed design.')
