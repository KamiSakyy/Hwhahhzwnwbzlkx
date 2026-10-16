package dev.themestudio.telegram;

import android.content.Context;
import android.graphics.Matrix;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.telegram.ui.Cells.ChatMessageCell;

/** No drawing. Keeps native scroll gestures; turns a short message tap into editor selection. */
final class EditorTouchLayer extends FrameLayout {
    interface Listener { void onSelect(MessageElementResolver.Hit hit); }
    private Listener listener;
    private MessageElementResolver.Hit downHit;
    private float downX, downY;
    private boolean moved;
    private final int slop;

    EditorTouchLayer(Context context, View officialView) {
        super(context);
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        addView(officialView, new FrameLayout.LayoutParams(-1, -1));
    }

    void setSelectionListener(Listener listener) {
        this.listener = listener;
        downHit = null;
        moved = true;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (listener == null) return super.dispatchTouchEvent(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            downX = event.getX(); downY = event.getY(); moved = false;
            downHit = find(this, downX, downY);
        } else if (action == MotionEvent.ACTION_MOVE) {
            float dx = event.getX() - downX, dy = event.getY() - downY;
            if (dx * dx + dy * dy > slop * slop) moved = true;
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            moved = true;
        } else if (action == MotionEvent.ACTION_UP) {
            MessageElementResolver.Hit hit = moved || event.getEventTime() - event.getDownTime() >= ViewConfiguration.getLongPressTimeout()
                    ? null : find(this, event.getX(), event.getY());
            if (downHit != null && hit != null && downHit.cell == hit.cell && downHit.message == hit.message && downHit.key.equals(hit.key)) {
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancel);
                cancel.recycle();
                downHit = null;
                listener.onSelect(hit);
                return true;
            }
            downHit = null;
        } else if (action == MotionEvent.ACTION_CANCEL) {
            downHit = null;
        }
        return super.dispatchTouchEvent(event);
    }

    private static MessageElementResolver.Hit find(View view, float x, float y) {
        if (view.getVisibility() != View.VISIBLE || view.getAlpha() == 0 || x < 0 || y < 0
                || x >= view.getWidth() || y >= view.getHeight()) return null;
        if (view instanceof ChatMessageCell) return MessageElementResolver.resolve((ChatMessageCell) view, x, y);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            // Respect Android elevation as well as child order. The stable sort preserves
            // the original order for equal Z values; transient custom drawing orders still
            // require runtime validation and are not claimed to be fully covered.
            java.util.ArrayList<View> children = new java.util.ArrayList<>();
            for (int i = 0; i < group.getChildCount(); i++) children.add(group.getChildAt(i));
            children.sort((left, right) -> Float.compare(left.getZ(), right.getZ()));
            for (int i = children.size() - 1; i >= 0; i--) {
                View child = children.get(i);
                float[] point = {x + group.getScrollX() - child.getLeft(), y + group.getScrollY() - child.getTop()};
                Matrix inverse = new Matrix();
                if (!child.getMatrix().invert(inverse)) continue;
                inverse.mapPoints(point);
                MessageElementResolver.Hit hit = find(child, point[0], point[1]);
                if (hit != null) return hit;
            }
        }
        return null;
    }
}
