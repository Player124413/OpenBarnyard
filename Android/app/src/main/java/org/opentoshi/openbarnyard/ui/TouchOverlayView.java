package org.opentoshi.openbarnyard.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import org.opentoshi.openbarnyard.core.GameCommand;
import org.opentoshi.openbarnyard.core.TouchProfile;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Renders and handles the on-screen controls for a {@link TouchProfile}.
 *
 * <p>Two modes share one renderer so what you see in the editor is exactly
 * what you get in game:
 * <ul>
 *   <li>GAME — multi-touch: virtual stick (fixed or floating origin),
 *       buttons mapped to engine AInputCommands, camera drag area.</li>
 *   <li>EDITOR — drag elements to move them (optional 1% grid snap),
 *       tap to select; geometry is mutated in the profile directly.</li>
 * </ul>
 * All coordinates in the profile are normalised (see TouchProfile), so a
 * layout survives rotation-free resolution/aspect changes untouched.
 */
public class TouchOverlayView extends View {
    public interface Listener {
        void onButton(int command, boolean down);
        /** x: -1..1 (right+), y: -1..1 (forward+). */
        void onStick(float x, float y);
        void onCameraDelta(float dxPx, float dyPx);
        void onSelect(TouchProfile.Element element);
        void onLayoutChanged();
    }

    private static final float STICK_DEADZONE = 0.12f;

    private TouchProfile profile;
    private boolean editorMode;
    private boolean snapEnabled = true;
    private boolean hapticsEnabled = true;
    private boolean russian;
    private Listener listener;

    // Live (game) state
    private final Map<Integer, TouchProfile.Element> pointerOwners = new HashMap<Integer, TouchProfile.Element>();
    private final Map<Integer, float[]> cameraLast = new HashMap<Integer, float[]>();
    private final Set<String> pressedIds = new HashSet<String>();
    private int stickPointer = -1;
    private float stickOriginX, stickOriginY; // px, current base centre
    private float stickKnobX, stickKnobY;     // px offset from origin
    private float stickValueX, stickValueY;

    // Editor state
    private TouchProfile.Element selected;
    private TouchProfile.Element dragElement;
    private float dragOffX, dragOffY; // px offset inside the element

    // Paints (allocated once)
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public TouchOverlayView(Context context) {
        super(context);
        strokePaint.setStyle(Paint.Style.STROKE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(Color.WHITE);
        gridPaint.setColor(Color.argb(36, 255, 255, 255));
        gridPaint.setStrokeWidth(1f);
    }

    // ------------------------------------------------------------ configuration

    public void setListener(Listener l) { listener = l; }
    public void setProfile(TouchProfile p) { profile = p; releaseAllPointers(); invalidate(); }
    public TouchProfile getProfile() { return profile; }
    public void setEditorMode(boolean editor) { editorMode = editor; releaseAllPointers(); invalidate(); }
    public boolean isEditorMode() { return editorMode; }
    public void setSnapEnabled(boolean snap) { snapEnabled = snap; }
    public void setHapticsEnabled(boolean on) { hapticsEnabled = on; }
    public void setRussian(boolean ru) { russian = ru; invalidate(); }
    public TouchProfile.Element getSelected() { return selected; }

    public void setSelected(TouchProfile.Element e) {
        selected = e;
        if (listener != null) listener.onSelect(e);
        invalidate();
    }

    /** Releases every held button/stick (pause, focus loss, profile swap). */
    public void releaseAllPointers() {
        if (listener != null) {
            for (TouchProfile.Element e : pointerOwners.values()) {
                if (e.type == TouchProfile.Type.BUTTON) listener.onButton(e.command, false);
            }
            if (stickPointer != -1) listener.onStick(0f, 0f);
        }
        pointerOwners.clear();
        cameraLast.clear();
        pressedIds.clear();
        stickPointer = -1;
        stickKnobX = stickKnobY = 0f;
        stickValueX = stickValueY = 0f;
        dragElement = null;
    }

    // ------------------------------------------------------------ geometry

    private float centerX(TouchProfile.Element e) { return e.x * getWidth(); }
    private float centerY(TouchProfile.Element e) { return e.y * getHeight(); }
    private float radius(TouchProfile.Element e) { return e.size * getHeight() * 0.5f; }

    private boolean hit(TouchProfile.Element e, float px, float py) {
        if (e.type == TouchProfile.Type.CAMERA) {
            float hw = e.w * getWidth() * 0.5f;
            float hh = e.h * getHeight() * 0.5f;
            return Math.abs(px - centerX(e)) <= hw && Math.abs(py - centerY(e)) <= hh;
        }
        float dx = px - centerX(e);
        float dy = py - centerY(e);
        float r = radius(e) * (e.type == TouchProfile.Type.STICK ? 1.35f : 1.2f);
        return dx * dx + dy * dy <= r * r;
    }

    /** Top-most element at the point; CAMERA areas always lose to buttons. */
    private TouchProfile.Element pick(float px, float py) {
        if (profile == null) return null;
        TouchProfile.Element camera = null;
        for (int i = profile.elements.size() - 1; i >= 0; i--) {
            TouchProfile.Element e = profile.elements.get(i);
            if (!hit(e, px, py)) continue;
            if (e.type == TouchProfile.Type.CAMERA) { if (camera == null) camera = e; }
            else return e;
        }
        return camera;
    }

    // ------------------------------------------------------------ input

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (profile == null || getWidth() == 0 || getHeight() == 0) return false;
        boolean handled = editorMode ? onEditorTouch(ev) : onGameTouch(ev);
        if (handled) invalidate();
        return handled;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private boolean onGameTouch(MotionEvent ev) {
        int action = ev.getActionMasked();
        int index = ev.getActionIndex();
        int pointerId = ev.getPointerId(index);
        float px = ev.getX(index);
        float py = ev.getY(index);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                performClick();
                TouchProfile.Element e = pick(px, py);
                if (e == null) return false;
                if (pointerOwners.containsValue(e) && e.type != TouchProfile.Type.CAMERA) return true;
                pointerOwners.put(pointerId, e);
                if (e.type == TouchProfile.Type.BUTTON) {
                    pressedIds.add(e.id);
                    if (listener != null) listener.onButton(e.command, true);
                    if (hapticsEnabled) performHaptic(HapticFeedbackConstants.VIRTUAL_KEY);
                } else if (e.type == TouchProfile.Type.STICK) {
                    stickPointer = pointerId;
                    if (profile.dynamicStick) {
                        stickOriginX = px;
                        stickOriginY = py;
                    } else {
                        stickOriginX = centerX(e);
                        stickOriginY = centerY(e);
                    }
                    updateStick(e, px, py);
                } else {
                    cameraLast.put(pointerId, new float[]{px, py});
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                // ACTION_MOVE batches pointers: walk every tracked pointer.
                for (int i = 0; i < ev.getPointerCount(); i++) {
                    int pid = ev.getPointerId(i);
                    TouchProfile.Element e = pointerOwners.get(pid);
                    if (e == null) continue;
                    float x = ev.getX(i), y = ev.getY(i);
                    if (e.type == TouchProfile.Type.STICK && pid == stickPointer) {
                        updateStick(e, x, y);
                    } else if (e.type == TouchProfile.Type.CAMERA) {
                        float[] last = cameraLast.get(pid);
                        if (last != null && listener != null) {
                            listener.onCameraDelta(x - last[0], y - last[1]);
                            last[0] = x; last[1] = y;
                        }
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                TouchProfile.Element e = pointerOwners.remove(pointerId);
                cameraLast.remove(pointerId);
                if (e != null) {
                    if (e.type == TouchProfile.Type.BUTTON) {
                        pressedIds.remove(e.id);
                        // Two on-screen buttons may share one command; only
                        // report "up" when the last holder lets go.
                        boolean stillHeld = false;
                        for (TouchProfile.Element other : pointerOwners.values()) {
                            if (other.type == TouchProfile.Type.BUTTON && other.command == e.command) {
                                stillHeld = true;
                                break;
                            }
                        }
                        if (listener != null && !stillHeld) listener.onButton(e.command, false);
                    } else if (e.type == TouchProfile.Type.STICK && pointerId == stickPointer) {
                        stickPointer = -1;
                        stickKnobX = stickKnobY = 0f;
                        stickValueX = stickValueY = 0f;
                        if (listener != null) listener.onStick(0f, 0f);
                    }
                }
                if (action == MotionEvent.ACTION_CANCEL) releaseAllPointers();
                return true;
            }
            default:
                return false;
        }
    }

    private void updateStick(TouchProfile.Element e, float px, float py) {
        float dx = px - stickOriginX;
        float dy = py - stickOriginY;
        float r = Math.max(1f, radius(e));
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len > r) { dx = dx / len * r; dy = dy / len * r; len = r; }
        stickKnobX = dx;
        stickKnobY = dy;
        float nx = dx / r;
        float ny = dy / r;
        float mag = (float) Math.sqrt(nx * nx + ny * ny);
        if (mag < STICK_DEADZONE) {
            nx = 0f; ny = 0f;
        } else {
            // Rescale so motion starts smoothly at the deadzone edge.
            float scaled = (mag - STICK_DEADZONE) / (1f - STICK_DEADZONE);
            nx = nx / mag * scaled;
            ny = ny / mag * scaled;
        }
        stickValueX = nx;
        stickValueY = ny;
        if (listener != null) listener.onStick(nx, -ny); // screen Y down → forward is +
    }

    private void performHaptic(int type) {
        try { performHapticFeedback(type); } catch (Throwable ignored) { /* some devices throw */ }
    }

    private boolean onEditorTouch(MotionEvent ev) {
        float px = ev.getX();
        float py = ev.getY();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                TouchProfile.Element e = pick(px, py);
                if (e != null) {
                    setSelected(e);
                    dragElement = e;
                    dragOffX = px - centerX(e);
                    dragOffY = py - centerY(e);
                    return true;
                }
                setSelected(null);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (dragElement == null) return false;
                float nx = (px - dragOffX) / getWidth();
                float ny = (py - dragOffY) / getHeight();
                if (snapEnabled) {
                    nx = Math.round(nx * 100f) / 100f;
                    ny = Math.round(ny * 100f) / 100f;
                }
                dragElement.x = clamp(nx, 0f, 1f);
                dragElement.y = clamp(ny, 0f, 1f);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (dragElement != null && listener != null) listener.onLayoutChanged();
                dragElement = null;
                return true;
            }
            default:
                return false;
        }
    }

    // ------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (profile == null || getWidth() == 0) return;
        if (editorMode) drawGrid(canvas);
        for (TouchProfile.Element e : profile.elements) {
            switch (e.type) {
                case CAMERA: if (editorMode) drawCamera(canvas, e); break;
                case STICK:  drawStick(canvas, e); break;
                case BUTTON: drawButton(canvas, e); break;
            }
        }
    }

    private void drawGrid(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        for (int i = 1; i < 10; i++) {
            float x = w * i / 10f;
            float y = h * i / 10f;
            canvas.drawLine(x, 0, x, h, gridPaint);
            canvas.drawLine(0, y, w, y, gridPaint);
        }
    }

    private void drawCamera(Canvas canvas, TouchProfile.Element e) {
        float hw = e.w * getWidth() * 0.5f;
        float hh = e.h * getHeight() * 0.5f;
        RectF rect = new RectF(centerX(e) - hw, centerY(e) - hh, centerX(e) + hw, centerY(e) + hh);
        fillPaint.setColor(Color.argb(28, 255, 255, 255));
        strokePaint.setColor(Color.argb(160, 255, 255, 255));
        strokePaint.setStrokeWidth(2f);
        strokePaint.setPathEffect(new DashPathEffect(new float[]{14f, 10f}, 0));
        canvas.drawRect(rect, fillPaint);
        canvas.drawRect(rect, strokePaint);
        strokePaint.setPathEffect(null);
        textPaint.setTextSize(getHeight() * 0.03f);
        canvas.drawText(russian ? "КАМЕРА" : "CAMERA", centerX(e), rect.top + getHeight() * 0.045f, textPaint);
        if (e == selected) drawSelection(canvas, centerX(e), centerY(e), Math.max(hw, hh));
    }

    private void drawStick(Canvas canvas, TouchProfile.Element e) {
        float r = radius(e);
        float ox = (stickPointer != -1 && !editorMode) ? stickOriginX : centerX(e);
        float oy = (stickPointer != -1 && !editorMode) ? stickOriginY : centerY(e);
        int alpha = (int) (e.opacity * 255);
        fillPaint.setColor(Color.argb(alpha * 2 / 5, 20, 24, 20));
        strokePaint.setColor(Color.argb(alpha, 255, 255, 255));
        strokePaint.setStrokeWidth(Math.max(2f, getHeight() * 0.004f));
        canvas.drawCircle(ox, oy, r, fillPaint);
        canvas.drawCircle(ox, oy, r, strokePaint);
        float kx = ox + (editorMode ? 0 : stickKnobX);
        float ky = oy + (editorMode ? 0 : stickKnobY);
        fillPaint.setColor(Color.argb(Math.min(255, alpha + 40), 255, 255, 255));
        canvas.drawCircle(kx, ky, r * 0.46f, fillPaint);
        if (editorMode) {
            textPaint.setTextSize(getHeight() * 0.03f);
            canvas.drawText(russian ? "СТИК" : "STICK", ox, oy + r + getHeight() * 0.04f, textPaint);
        }
        if (e == selected && editorMode) drawSelection(canvas, ox, oy, r);
    }

    private void drawButton(Canvas canvas, TouchProfile.Element e) {
        float r = radius(e);
        float cx = centerX(e), cy = centerY(e);
        boolean pressed = pressedIds.contains(e.id);
        int alpha = (int) (e.opacity * 255);
        fillPaint.setColor(pressed
            ? Color.argb(Math.min(255, alpha + 50), 255, 214, 120)
            : Color.argb(alpha * 3 / 5, 20, 24, 20));
        strokePaint.setColor(Color.argb(alpha, 255, 255, 255));
        strokePaint.setStrokeWidth(Math.max(2f, getHeight() * 0.004f));
        canvas.drawCircle(cx, cy, r, fillPaint);
        canvas.drawCircle(cx, cy, r, strokePaint);

        String label = e.displayLabel();
        textPaint.setColor(Color.argb(Math.max(alpha, 170), 255, 255, 255));
        textPaint.setTextSize(Math.max(11f, r * (label.length() > 2 ? 0.52f : 0.85f)));
        // Optical centring: baseline ≈ centre + 0.35 * text height
        canvas.drawText(label, cx, cy + textPaint.getTextSize() * 0.35f, textPaint);
        if (editorMode) {
            GameCommand c = GameCommand.byId(e.command);
            textPaint.setTextSize(getHeight() * 0.026f);
            canvas.drawText(c != null ? c.label(russian) : ("#" + e.command),
                cx, cy + r + getHeight() * 0.038f, textPaint);
        }
        if (e == selected && editorMode) drawSelection(canvas, cx, cy, r);
    }

    private void drawSelection(Canvas canvas, float cx, float cy, float r) {
        strokePaint.setColor(Color.rgb(0xFF, 0xC4, 0x4D));
        strokePaint.setStrokeWidth(Math.max(3f, getHeight() * 0.006f));
        canvas.drawCircle(cx, cy, r + getHeight() * 0.012f, strokePaint);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
