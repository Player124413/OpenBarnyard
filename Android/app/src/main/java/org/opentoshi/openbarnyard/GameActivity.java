package org.opentoshi.openbarnyard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.opentoshi.openbarnyard.core.EngineConfig;
import org.opentoshi.openbarnyard.core.GameCommand;
import org.opentoshi.openbarnyard.core.TouchProfile;
import org.opentoshi.openbarnyard.ui.TouchOverlayView;
import org.opentoshi.openbarnyard.ui.UiKit;

import java.util.HashSet;
import java.util.Set;

/**
 * The game screen: native surface + live touch overlay + FPS readout.
 * Physical keyboards and gamepads are translated to the same engine
 * commands, so all input paths share one pipeline.
 */
public class GameActivity extends Activity implements SurfaceHolder.Callback, TouchOverlayView.Listener {
    private TouchOverlayView overlay;
    private TextView fpsView;
    private EngineConfig config;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean surfaceReady;
    private final Set<Integer> heldKeys = new HashSet<Integer>();

    private final Runnable fpsTick = new Runnable() {
        @Override public void run() {
            if (fpsView != null && config != null && config.showFps) {
                fpsView.setText(getString(R.string.fps_format,
                    NativeBridge.getFps(), Math.round(NativeBridge.getCurrentScale() * 100f)));
            }
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        config = Storage.loadConfig(this);

        FrameLayout root = new FrameLayout(this);

        SurfaceView surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        root.addView(surface, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        overlay = new TouchOverlayView(this);
        overlay.setListener(this);
        overlay.setEditorMode(false);
        overlay.setRussian(Storage.isRussian());
        overlay.setHapticsEnabled(config.haptics);
        overlay.setProfile(Storage.loadActiveProfile(this));
        root.addView(overlay, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Demo banner (honest status line, dismisses itself after a while)
        final TextView banner = new TextView(this);
        banner.setText(R.string.game_demo_banner);
        banner.setTextColor(Color.WHITE);
        banner.setTextSize(12);
        banner.setBackgroundColor(Color.argb(140, 20, 28, 20));
        banner.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 5), UiKit.dp(this, 10), UiKit.dp(this, 5));
        FrameLayout.LayoutParams bannerLp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        bannerLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        bannerLp.topMargin = UiKit.dp(this, 6);
        root.addView(banner, bannerLp);
        handler.postDelayed(new Runnable() {
            @Override public void run() { banner.setVisibility(View.GONE); }
        }, 9000);

        fpsView = new TextView(this);
        fpsView.setTextColor(Color.WHITE);
        fpsView.setTextSize(12);
        fpsView.setBackgroundColor(Color.argb(120, 20, 28, 20));
        fpsView.setPadding(UiKit.dp(this, 8), UiKit.dp(this, 4), UiKit.dp(this, 8), UiKit.dp(this, 4));
        fpsView.setVisibility(config.showFps ? View.VISIBLE : View.GONE);
        FrameLayout.LayoutParams fpsLp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        fpsLp.gravity = Gravity.TOP | Gravity.LEFT;
        fpsLp.leftMargin = UiKit.dp(this, 8);
        fpsLp.topMargin = UiKit.dp(this, 8);
        root.addView(fpsView, fpsLp);

        TextView pauseBtn = new TextView(this);
        pauseBtn.setText("⏸");
        pauseBtn.setTextColor(Color.WHITE);
        pauseBtn.setTextSize(18);
        pauseBtn.setBackgroundColor(Color.argb(120, 20, 28, 20));
        pauseBtn.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 6), UiKit.dp(this, 10), UiKit.dp(this, 6));
        pauseBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showPause(); }
        });
        FrameLayout.LayoutParams pauseLp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        pauseLp.gravity = Gravity.TOP | Gravity.RIGHT;
        pauseLp.rightMargin = UiKit.dp(this, 8);
        pauseLp.topMargin = UiKit.dp(this, 8);
        root.addView(pauseBtn, pauseLp);

        setContentView(root);
        enterImmersive();
    }

    private void enterImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersive();
        else if (overlay != null) overlay.releaseAllPointers();
    }

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        config = Storage.loadConfig(this);
        overlay.setHapticsEnabled(config.haptics);
        overlay.setProfile(Storage.loadActiveProfile(this));
        fpsView.setVisibility(config.showFps ? View.VISIBLE : View.GONE);
        NativeBridge.init(getFilesDir().getAbsolutePath(),
            Storage.gameDataPathForNative(this), config.toJson());
        NativeBridge.setResumed(true);
        handler.removeCallbacks(fpsTick);
        handler.post(fpsTick);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(fpsTick);
        if (overlay != null) overlay.releaseAllPointers();
        NativeBridge.setResumed(false);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (isFinishing()) NativeBridge.shutdown();
        super.onDestroy();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        Surface surface = holder.getSurface();
        if (surface != null && surface.isValid()) NativeBridge.setSurface(surface);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // The native side polls EGL surface size every frame; nothing to do.
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        NativeBridge.clearSurface();
    }

    // ------------------------------------------------------------ touch input → native

    @Override public void onButton(int command, boolean down) {
        if (command == GameCommand.MENU.id && down) {
            showPause();
            return;
        }
        NativeBridge.setButton(command, down);
    }

    @Override public void onStick(float x, float y) { NativeBridge.setStick(x, y); }

    @Override public void onCameraDelta(float dxPx, float dyPx) {
        NativeBridge.addCameraDelta(dxPx, dyPx);
    }

    @Override public void onSelect(TouchProfile.Element element) { /* editor only */ }
    @Override public void onLayoutChanged() { /* editor only */ }

    // ------------------------------------------------------------ keyboard / gamepad

    private Integer mapDigitalKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_BUTTON_A: return GameCommand.JUMP.id;
            case KeyEvent.KEYCODE_E:
            case KeyEvent.KEYCODE_BUTTON_X: return GameCommand.INTERACT.id;
            case KeyEvent.KEYCODE_F:
            case KeyEvent.KEYCODE_BUTTON_Y: return GameCommand.USE.id;
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
            case KeyEvent.KEYCODE_BUTTON_B: return GameCommand.SPEED.id;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER: return GameCommand.OK.id;
            case KeyEvent.KEYCODE_I:
            case KeyEvent.KEYCODE_BUTTON_SELECT: return GameCommand.INVENTORY.id;
            case KeyEvent.KEYCODE_M: return GameCommand.ICONS.id;
            default: return null;
        }
    }

    private void recomputeKeyStick() {
        float x = 0, y = 0;
        if (heldKeys.contains(KeyEvent.KEYCODE_A) || heldKeys.contains(KeyEvent.KEYCODE_DPAD_LEFT)) x -= 1;
        if (heldKeys.contains(KeyEvent.KEYCODE_D) || heldKeys.contains(KeyEvent.KEYCODE_DPAD_RIGHT)) x += 1;
        if (heldKeys.contains(KeyEvent.KEYCODE_W) || heldKeys.contains(KeyEvent.KEYCODE_DPAD_UP)) y += 1;
        if (heldKeys.contains(KeyEvent.KEYCODE_S) || heldKeys.contains(KeyEvent.KEYCODE_DPAD_DOWN)) y -= 1;
        NativeBridge.setStick(x, y);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE
            || keyCode == KeyEvent.KEYCODE_BUTTON_START) {
            showPause();
            return true;
        }
        Integer cmd = mapDigitalKey(keyCode);
        if (cmd != null) {
            if (event.getRepeatCount() == 0) NativeBridge.setButton(cmd, true);
            return true;
        }
        if (isMovementKey(keyCode)) {
            heldKeys.add(keyCode);
            recomputeKeyStick();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        Integer cmd = mapDigitalKey(keyCode);
        if (cmd != null) {
            NativeBridge.setButton(cmd, false);
            return true;
        }
        if (isMovementKey(keyCode)) {
            heldKeys.remove(keyCode);
            recomputeKeyStick();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private boolean isMovementKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_W || keyCode == KeyEvent.KEYCODE_A
            || keyCode == KeyEvent.KEYCODE_S || keyCode == KeyEvent.KEYCODE_D
            || keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
            || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_JOYSTICK) != 0
            && event.getAction() == MotionEvent.ACTION_MOVE) {
            float lx = deadzone(event.getAxisValue(MotionEvent.AXIS_X));
            float ly = deadzone(event.getAxisValue(MotionEvent.AXIS_Y));
            NativeBridge.setStick(lx, -ly);
            float rx = deadzone(event.getAxisValue(MotionEvent.AXIS_Z));
            float ry = deadzone(event.getAxisValue(MotionEvent.AXIS_RZ));
            if (rx != 0 || ry != 0) NativeBridge.addCameraDelta(rx * 22f, ry * 22f);
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    private float deadzone(float v) {
        return Math.abs(v) < 0.15f ? 0f : v;
    }

    // ------------------------------------------------------------ pause menu

    @Override
    public void onBackPressed() {
        showPause();
    }

    private void showPause() {
        if (isFinishing() || isDestroyed()) return;
        overlay.releaseAllPointers();
        String[] items = {
            getString(R.string.btn_resume),
            getString(R.string.btn_edit_controls),
            getString(R.string.btn_settings),
            getString(R.string.btn_exit_launcher)
        };
        new AlertDialog.Builder(this)
            .setTitle(R.string.pause_title)
            .setItems(items, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    switch (which) {
                        case 0: enterImmersive(); break;
                        case 1: startActivity(new Intent(GameActivity.this, TouchEditorActivity.class)); break;
                        case 2: startActivity(new Intent(GameActivity.this, SettingsActivity.class)); break;
                        case 3: finish(); break;
                    }
                }
            })
            .setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override public void onDismiss(DialogInterface dialog) { enterImmersive(); }
            })
            .show();
    }
}
