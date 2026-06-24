package com.zomdroid;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.system.ErrnoException;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.zomdroid.input.GLFWBinding;
import com.zomdroid.input.GamepadManager;
import com.zomdroid.input.InputControlsView;
import com.zomdroid.input.InputNativeInterface;
import com.zomdroid.input.KeyboardManager;
import com.zomdroid.databinding.ActivityGameBinding;
import com.zomdroid.game.GameInstance;
import com.zomdroid.game.GameInstanceManager;

import org.fmod.FMOD;

public class GameActivity extends AppCompatActivity
        implements GamepadManager.GamepadListener, KeyboardManager.KeyboardListener {

    public static final String EXTRA_GAME_INSTANCE_NAME = "com.zomdroid.GameActivity.EXTRA_GAME_INSTANCE_NAME";
    private static final String LOG_TAG = GameActivity.class.getName();

    private ActivityGameBinding binding;
    private Surface gameSurface;
    private static boolean isGameStarted = false;
    private Thread gameThread;

    private GamepadManager gamepadManager;
    private KeyboardManager keyboardManager;

    private boolean isGamepadConnected = false;
    private boolean isKeyboardConnected = false;

    private boolean leftMouseDown = false;
    private boolean rightMouseDown = false;
    private boolean systemKeyboardVisible = false;

    private float renderScale = 1f;

    @SuppressLint({"UnsafeDynamicallyLoadedCode", "ClickableViewAccessibility"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityGameBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.gameSv.setFocusable(true);
        binding.gameSv.setFocusableInTouchMode(true);
        binding.gameSv.requestFocus();

        renderScale = LauncherPreferences.requireSingleton().getRenderScale();

        try {
            gamepadManager = new GamepadManager(this, this);
            boolean isTouchEnabled = LauncherPreferences.requireSingleton().isTouchControlsEnabled();
            GamepadManager.setTouchOverride(isTouchEnabled);
        } catch (Exception e) {
            Log.e(LOG_TAG, "Failed to initialize GamepadManager", e);
            gamepadManager = null;
        }

        try {
            keyboardManager = new KeyboardManager(this, this);
            boolean isTouchEnabled = LauncherPreferences.requireSingleton().isTouchControlsEnabled();
            KeyboardManager.setTouchOverride(isTouchEnabled);
        } catch (Exception e) {
            Log.e(LOG_TAG, "Failed to initialize KeyboardManager", e);
            keyboardManager = null;
        }

        applyInputOverlay();
        binding.inputControlsV.setKeyboardToggleListener(this::toggleSystemKeyboard);
        binding.inputControlsV.setRenderScale(renderScale);

        getWindow().setDecorFitsSystemWindows(false);
        final WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

        String gameInstanceName = getIntent().getStringExtra(EXTRA_GAME_INSTANCE_NAME);
        if (gameInstanceName == null)
            throw new RuntimeException("Expected game instance name to be passed as intent extra");
        GameInstance gameInstance = GameInstanceManager.requireSingleton().getInstanceByName(gameInstanceName);
        if (gameInstance == null)
            throw new RuntimeException("Game instance with name " + gameInstanceName + " not found");

        System.loadLibrary("zomdroid");

        System.load(AppStorage.requireSingleton().getHomePath() + "/" + gameInstance.getFmodLibraryPath() + "/libfmod.so");
        System.load(AppStorage.requireSingleton().getHomePath() + "/" + gameInstance.getFmodLibraryPath() + "/libfmodstudio.so");

        FMOD.init(this);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                showExitGameDialog();
            }
        });

        binding.gameSv.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                Log.d(LOG_TAG, "Game surface created.");
                renderScale = LauncherPreferences.requireSingleton().getRenderScale();
                int width = (int) (binding.gameSv.getWidth() * renderScale);
                int height = (int) (binding.gameSv.getHeight() * renderScale);
                binding.gameSv.getHolder().setFixedSize(width, height);
                binding.inputControlsV.setRenderScale(renderScale);
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                Log.d(LOG_TAG, "Game surface changed.");
                gameSurface = binding.gameSv.getHolder().getSurface();
                if (gameSurface == null) throw new RuntimeException();

                if (format != PixelFormat.RGBA_8888) {
                    Log.w(LOG_TAG, "Using unsupported pixel format " + format);
                }

                GameLauncher.setSurface(gameSurface, width, height);

                if (!isGameStarted) {
                    gameThread = new Thread(() -> {
                        try {
                            GameLauncher.launch(gameInstance);
                        } catch (ErrnoException e) {
                            throw new RuntimeException(e);
                        }
                    });
                    gameThread.start();
                    isGameStarted = true;
                }
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                Log.d(LOG_TAG, "Game surface destroyed.");
                GameLauncher.destroySurface();
            }
        });

        binding.gameSv.setOnTouchListener(new View.OnTouchListener() {
            int activePointerId = -1;
            boolean leftPressedFinger = false;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (binding.inputControlsV != null
                        && binding.inputControlsV.getVisibility() == View.VISIBLE
                        && binding.inputControlsV.onTouchEvent(e)) {
                    return true;
                }

                int action = e.getActionMasked();
                int idx = e.getActionIndex();

                switch (action) {
                    case MotionEvent.ACTION_DOWN:
                    case MotionEvent.ACTION_POINTER_DOWN: {
                        activePointerId = e.getPointerId(idx);
                        float x = e.getX(idx), y = e.getY(idx);
                        InputNativeInterface.sendCursorPos(x * renderScale, y * renderScale);
                        leftPressedFinger = true;
                        leftMouseDown = true;
                        InputNativeInterface.sendMouseButton(GLFWBinding.MOUSE_BUTTON_LEFT.code, true);
                        if (isMouseEvent(e, idx)) {
                            syncMouseReleaseFromMask(e.getButtonState());
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_MOVE: {
                        if (activePointerId < 0) return false;
                        int p = e.findPointerIndex(activePointerId);
                        if (p < 0) { activePointerId = -1; return false; }
                        float x = e.getX(p), y = e.getY(p);
                        InputNativeInterface.sendCursorPos(x * renderScale, y * renderScale);
                        if (isMouseEvent(e, p)) {
                            syncMouseReleaseFromMask(e.getButtonState());
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_POINTER_UP: {
                        if (activePointerId < 0) return false;
                        float x = e.getX(idx), y = e.getY(idx);
                        if (leftPressedFinger) {
                            InputNativeInterface.sendMouseButton(GLFWBinding.MOUSE_BUTTON_LEFT.code, false);
                            leftPressedFinger = false;
                        }
                        leftMouseDown = false;
                        InputNativeInterface.sendCursorPos(x * renderScale, y * renderScale);
                        if (isMouseEvent(e, idx)) {
                            syncMouseReleaseFromMask(e.getButtonState());
                        }
                        activePointerId = -1;
                        return true;
                    }
                    case MotionEvent.ACTION_CANCEL: {
                        if (leftPressedFinger || leftMouseDown) {
                            InputNativeInterface.sendMouseButton(GLFWBinding.MOUSE_BUTTON_LEFT.code, false);
                            leftPressedFinger = false;
                            leftMouseDown = false;
                        }
                        activePointerId = -1;
                        return true;
                    }
                }
                return false;
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (gamepadManager != null) gamepadManager.register();
        if (keyboardManager != null) keyboardManager.register();
    }

    @Override
    protected void onPause() {
        if (gamepadManager != null) gamepadManager.unregister();
        if (keyboardManager != null) keyboardManager.unregister();
        try { GameLauncher.destroySurface(); } catch (Throwable ignored) {}
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        cleanupGameRuntime();
        super.onDestroy();
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    // GamepadManager.GamepadListener

    @Override
    public void onGamepadConnected() {
        isGamepadConnected = true;
        applyInputOverlay();
    }

    @Override
    public void onGamepadDisconnected() {
        isGamepadConnected = false;
        applyInputOverlay();
    }

    @Override
    public void onGamepadButton(int button, boolean pressed) {
        InputNativeInterface.sendJoystickButton(button, pressed);
    }

    @Override
    public void onGamepadAxis(int axis, float value) {
        InputNativeInterface.sendJoystickAxis(axis, value);
    }

    @Override
    public void onGamepadDpad(int dpad, char state) {
        InputNativeInterface.sendJoystickDpad(dpad, state);
    }

    // KeyboardManager.KeyboardListener

    @Override
    public void onKeyboardConnected() {
        isKeyboardConnected = true;
        systemKeyboardVisible = false;
        hideSystemKeyboard();
        binding.inputControlsV.setKeyboardConnected(true);
        reapplyImmersiveMode();
        applyInputOverlay();
    }

    @Override
    public void onKeyboardDisconnected() {
        isKeyboardConnected = false;
        binding.inputControlsV.setKeyboardConnected(false);
        applyInputOverlay();
    }

    @Override
    public void onKeyboardKey(int glfwCode, boolean pressed) {
        InputNativeInterface.sendKeyboard(glfwCode, pressed);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean handled = false;
        if (keyboardManager != null) handled |= keyboardManager.handleKeyEvent(event);
        if (isGamepadConnected && gamepadManager != null) handled |= gamepadManager.handleKeyEvent(event);
        if (handled) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        boolean handled = false;
        if (keyboardManager != null) handled |= keyboardManager.handleKeyEvent(event);
        if (isGamepadConnected && gamepadManager != null) handled |= gamepadManager.handleKeyEvent(event);
        if (handled) return true;
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        boolean isPointerDevice = event.isFromSource(InputDevice.SOURCE_MOUSE)
                || event.isFromSource(InputDevice.SOURCE_TOUCHPAD)
                || event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE;

        if (!isPointerDevice) {
            if (gamepadManager != null && gamepadManager.handleMotionEvent(event)) return true;
            return super.onGenericMotionEvent(event);
        }

        int action = event.getActionMasked();
        int btn = event.getActionButton();

        if (action == MotionEvent.ACTION_HOVER_MOVE) {
            float x = event.getX();
            float y = event.getY();
            InputNativeInterface.sendCursorPos(x * renderScale, y * renderScale);
            syncMouseReleaseFromMask(event.getButtonState());
            return true;
        }

        if (action == MotionEvent.ACTION_SCROLL) {
            float v = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if (v == 0) v = event.getAxisValue(MotionEvent.AXIS_WHEEL);
            if (v != 0) {
                InputNativeInterface.sendMouseScroll(0.0, v > 0 ? 1.0 : -1.0);
            }
            return true;
        }

        if (action == MotionEvent.ACTION_BUTTON_PRESS || action == MotionEvent.ACTION_BUTTON_RELEASE) {
            boolean pressed = (action == MotionEvent.ACTION_BUTTON_PRESS);
            InputNativeInterface.sendCursorPos(event.getX() * renderScale, event.getY() * renderScale);

            if (btn == MotionEvent.BUTTON_PRIMARY) {
                leftMouseDown = pressed;
                InputNativeInterface.sendMouseButton(GLFWBinding.MOUSE_BUTTON_LEFT.code, pressed);
                syncMouseReleaseFromMask(event.getButtonState());
                return true;
            } else if (btn == MotionEvent.BUTTON_SECONDARY) {
                rightMouseDown = pressed;
                InputNativeInterface.sendMouseButton(GLFWBinding.MOUSE_BUTTON_RIGHT.code, pressed);
                syncMouseReleaseFromMask(event.getButtonState());
                return true;
            }
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int kc = event.getKeyCode();
        if (kc == KeyEvent.KEYCODE_BACK
                || kc == KeyEvent.KEYCODE_VOLUME_UP
                || kc == KeyEvent.KEYCODE_VOLUME_DOWN
                || kc == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(event);
        }

        boolean physicalKeyboardEvent = isTruePhysicalKeyboardEvent(event);
        boolean textInputMode = systemKeyboardVisible;

        if (isKeyboardConnected && physicalKeyboardEvent && !textInputMode) {
            if (keyboardManager != null && keyboardManager.handleKeyEvent(event)) {
                return true;
            }
            return true;
        }

        return super.dispatchKeyEvent(event);
    }

    private boolean isTruePhysicalKeyboardEvent(KeyEvent event) {
        InputDevice device = event.getDevice();
        if (device == null) return false;
        boolean isGamepad = (device.getSources() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
        if (isGamepad) return false;
        return !device.isVirtual()
                && (event.isFromSource(InputDevice.SOURCE_KEYBOARD)
                || (device.getSources() & InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD);
    }

    private void applyInputOverlay() {
        if (binding.inputControlsV == null) return;
        binding.inputControlsV.setGamepadConnected(isGamepadConnected);

        if (isKeyboardConnected) {
            binding.inputControlsV.setVisibility(View.GONE);
        } else if (isGamepadConnected) {
            binding.inputControlsV.setVisibility(View.VISIBLE);
            binding.inputControlsV.applyInputMode(InputControlsView.InputMode.MNK);
        } else {
            binding.inputControlsV.setVisibility(View.VISIBLE);
            binding.inputControlsV.applyInputMode(InputControlsView.InputMode.ALL);
        }
    }

    private void syncMouseReleaseFromMask(int mask) {
        boolean leftNow  = (mask & MotionEvent.BUTTON_PRIMARY)   != 0;
        boolean rightNow = (mask & MotionEvent.BUTTON_SECONDARY) != 0;

        if (!leftNow && leftMouseDown) {
            leftMouseDown = false;
            InputNativeInterface.sendMouseButton(GLFWBinding.MOUSE_BUTTON_LEFT.code, false);
        }
        if (!rightNow && rightMouseDown) {
            rightMouseDown = false;
            InputNativeInterface.sendMouseButton(GLFWBinding.MOUSE_BUTTON_RIGHT.code, false);
        }
    }

    private boolean isMouseEvent(MotionEvent e, int pointerIndex) {
        return e.isFromSource(InputDevice.SOURCE_MOUSE)
                || e.isFromSource(InputDevice.SOURCE_TOUCHPAD)
                || (pointerIndex >= 0 && pointerIndex < e.getPointerCount()
                && e.getToolType(pointerIndex) == MotionEvent.TOOL_TYPE_MOUSE);
    }

    public void showSystemKeyboard() {
        binding.gameSv.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(binding.gameSv, InputMethodManager.SHOW_FORCED);
        }
    }

    public void hideSystemKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(binding.gameSv.getWindowToken(), 0);
        }
    }

    private void toggleSystemKeyboard() {
        if (isKeyboardConnected) return;
        systemKeyboardVisible = !systemKeyboardVisible;
        if (systemKeyboardVisible) {
            showSystemKeyboard();
        } else {
            hideSystemKeyboard();
        }
    }

    private void reapplyImmersiveMode() {
        final WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            controller.hide(WindowInsets.Type.statusBars()
                    | WindowInsets.Type.navigationBars()
                    | WindowInsets.Type.ime());
            controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        binding.gameSv.requestFocus();
    }

    private void showExitGameDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_close_game_title)
                .setMessage(R.string.dialog_close_game_message)
                .setNegativeButton(R.string.dialog_button_cancel, (d, which) -> d.dismiss())
                .setPositiveButton(R.string.dialog_button_ok, (d, which) -> exitGameToLauncher())
                .setCancelable(true)
                .show();
    }

    private void exitGameToLauncher() {
        cleanupGameRuntime();
        Intent intent = new Intent(this, LauncherActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    private void cleanupGameRuntime() {
        try { GameLauncher.destroySurface(); } catch (Throwable ignored) {}
        try { GameLauncher.destroyZomdroidWindow(); } catch (Throwable ignored) {}
        try { FMOD.close(); } catch (Throwable ignored) {}

        if (gamepadManager != null) gamepadManager.unregister();
        if (keyboardManager != null) keyboardManager.unregister();

        if (gameThread != null && gameThread.isAlive()) {
            try { gameThread.join(5000); } catch (InterruptedException ignored) {}
            if (gameThread.isAlive()) {
                Log.w(LOG_TAG, "Game thread did not exit in time, force-interrupting");
                gameThread.interrupt();
            }
        }

        isGameStarted = false;
        gameThread = null;
    }
}
