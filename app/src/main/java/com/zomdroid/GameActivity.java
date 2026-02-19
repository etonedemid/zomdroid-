package com.zomdroid;

import android.annotation.SuppressLint;
import android.content.pm.ActivityInfo;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.system.ErrnoException;
import android.util.Log;
import android.view.GestureDetector;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.zomdroid.input.GLFWBinding;
import com.zomdroid.input.ExternalControllerConfig;
import com.zomdroid.input.InputNativeInterface;
import com.zomdroid.databinding.ActivityGameBinding;
import com.zomdroid.game.GameInstance;
import com.zomdroid.game.GameInstanceManager;

import org.fmod.FMOD;

import java.lang.ref.WeakReference;


public class GameActivity extends AppCompatActivity {
    public static final String EXTRA_GAME_INSTANCE_NAME = "com.zomdroid.GameActivity.EXTRA_GAME_INSTANCE_NAME";
    private static final String LOG_TAG = GameActivity.class.getName();

    private ActivityGameBinding binding;
    private Surface gameSurface;
    private static boolean isGameStarted = false;
    private GestureDetector gestureDetector;
    private ExternalControllerConfig externalControllerConfig;
    private int dpadState = 0;
    private boolean sentJoystickConnected = false;

    @SuppressLint({"UnsafeDynamicallyLoadedCode", "ClickableViewAccessibility"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityGameBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

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
/*        System.loadLibrary("fmod");
        System.loadLibrary("fmodstudio");*/

        FMOD.init(this);

        externalControllerConfig = ExternalControllerConfig.load(this);

        // If external controller config disables overlay controls, hide overlay
        if (!externalControllerConfig.overlayControlsEnabled) {
            binding.inputControlsV.setOverlayEnabled(false);
        }

/*        gestureDetector = new GestureDetector(this, new GestureDetector.OnGestureListener() {
            private boolean showPress = false;
            @Override
            public boolean onDown(@NonNull MotionEvent e) {
                Log.v("", "onDown " + e.getX() + " " + e.getY());
                //InputBridge.sendMouseButton(GLFWConstants.GLFW_MOUSE_BUTTON_LEFT, GLFWConstants.GLFW_PRESS, event.getX(), event.getY());
                return true;
            }

            @Override
            public void onShowPress(@NonNull MotionEvent e) {
                Log.v("", "onShowPress " + e.getX() + " " + e.getY());
                showPress = true;
                InputNativeInterface.sendCursorPos(e.getX(), e.getY());
                InputNativeInterface.sendMouseButton(GLFWBinding.MOUSE_BUTTON_LEFT.code, true);
            }

            @Override
            public boolean onSingleTapUp(@NonNull MotionEvent e) {
                Log.v("", "onSingleTapUp " + e.getX() + " " + e.getY());
                InputNativeInterface.sendCursorPos(e.getX(), e.getY());
                if (showPress) {
                    InputNativeInterface.sendMouseButton(GLFWBinding.MOUSE_BUTTON_LEFT.code, false);
                }
                showPress = false;
                return true;
            }

            @Override
            public boolean onScroll(@Nullable MotionEvent e1, @NonNull MotionEvent e2, float distanceX, float distanceY) {
                InputNativeInterface.sendCursorPos(e2.getX(), e2.getY());
                Log.v("", "onScroll " + (e1 == null ? "0" : e1.getX()) + " " + (e1 == null ? "0" : e1.getY()) + " " + e2.getX() + " " + e2.getY());
                return true;
            }

            @Override
            public void onLongPress(@NonNull MotionEvent e) {
                Log.v("", "onLongPress " + e.getX() + " " + e.getY());
            }

            @Override
            public boolean onFling(@Nullable MotionEvent e1, @NonNull MotionEvent e2, float velocityX, float velocityY) {
                Log.v("", "onFling " + velocityX + " " + velocityY);
                return true;
            }
        });
        gestureDetector.setIsLongpressEnabled(false);*/

        binding.gameSv.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                Log.d(LOG_TAG, "Game surface created.");
                float renderScale = LauncherPreferences.requireSingleton().getRenderScale();
                int width = (int) (binding.gameSv.getWidth() * renderScale);
                int height = (int) (binding.gameSv.getHeight() * renderScale);
                binding.gameSv.getHolder().setFixedSize(width, height);
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                Log.d(LOG_TAG, "Game surface changed.");


                gameSurface = binding.gameSv.getHolder().getSurface();
                if (gameSurface == null) throw new RuntimeException();

                if (format != PixelFormat.RGBA_8888) {
                    Log.w(LOG_TAG, "Using unsupported pixel format " + format); // LIAMELUI seems like default is RGB_565
                }

                GameLauncher.setSurface(gameSurface, width, height);
                if (!isGameStarted) {
                    Thread thread = new Thread(() -> {
                        try {
                            GameLauncher.launch(gameInstance);
                        } catch (ErrnoException e) {
                            throw new RuntimeException(e);
                        }
                    });
                    thread.start();
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
            float renderScale = LauncherPreferences.requireSingleton().getRenderScale();
            int pointerId = -1;

            @Override
            public boolean onTouch(View v, MotionEvent e) { // this should be in InputControlsView
                int action = e.getActionMasked();
                int actionIndex = e.getActionIndex();
                int pointerId = e.getPointerId(actionIndex);
                switch (action) {
                    case MotionEvent.ACTION_DOWN:
                    case MotionEvent.ACTION_POINTER_DOWN: {
                        float x = e.getX(actionIndex);
                        float y = e.getY(actionIndex);
                        this.pointerId = pointerId;
                        InputNativeInterface.sendCursorPos(x * this.renderScale, y * this.renderScale);
                        InputNativeInterface.sendMouseButton(GLFWBinding.MOUSE_BUTTON_LEFT.code, true);
                        // if overlay is visible, prefer overlay touch handling (controls view) by returning false
                        if (binding.inputControlsV.isOverlayEnabled()) return false;
                        return true;
                    }
                    case MotionEvent.ACTION_MOVE: {
                        if (this.pointerId < 0) return false;
                        int pointerIndex = e.findPointerIndex(this.pointerId);
                        if (pointerIndex < 0) {
                            this.pointerId = -1;
                            return false;
                        }
                        float x = e.getX(pointerIndex);
                        float y = e.getY(pointerIndex);
                        InputNativeInterface.sendCursorPos(x * this.renderScale, y * this.renderScale);
                        if (binding.inputControlsV.isOverlayEnabled()) return false;
                        return false;
                    }
                    case MotionEvent.ACTION_UP: {
                        if (pointerId != this.pointerId) return false;
                        this.pointerId = -1;
                        InputNativeInterface.sendMouseButton(GLFWBinding.MOUSE_BUTTON_LEFT.code, false);
                        return true;
                    }
                }
                return false;
            }
        });

        // add in-game overlay toggle button (top-right)
        binding.getRoot().post(() -> {
            android.widget.ImageButton toggle = new android.widget.ImageButton(this);
            toggle.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
            // match transparency to overlay controls opacity
            int alpha = Math.round(binding.inputControlsV.getOverlayOpacityPercent() / 100f * 255f);
            int bgColor = (alpha << 24) | 0x000000;
            toggle.setBackgroundColor(bgColor);
            toggle.setImageAlpha(alpha);
            int size = (int) (56 * getResources().getDisplayMetrics().density);
            android.widget.FrameLayout.LayoutParams params = new android.widget.FrameLayout.LayoutParams(size, size);
            params.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
            params.setMargins(16, 16, 16, 16);
            toggle.setLayoutParams(params);
            toggle.setOnClickListener(v -> {
                boolean enabled = !binding.inputControlsV.isOverlayEnabled();
                binding.inputControlsV.setOverlayEnabled(enabled);
                getSharedPreferences(C.shprefs.NAME, MODE_PRIVATE)
                        .edit()
                        .putBoolean(C.shprefs.keys.OVERLAY_ENABLED, enabled)
                        .apply();
            });
            binding.getRoot().addView(toggle);
        });

    }

    @Override
    public void onBackPressed() {
        // Show confirmation dialog to close the game and return to the menu
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_close_game_title)
                .setMessage(R.string.dialog_close_game_message)
                .setNegativeButton(R.string.dialog_button_cancel, (d, which) -> d.dismiss())
                .setPositiveButton(R.string.dialog_button_ok, (d, which) -> {
                    // finish activity and return to launcher/menu
                    finish();
                })
                .setCancelable(true)
                .show();
    }

    public void setOverlayEnabled(boolean enabled) {
        if (binding != null) binding.inputControlsV.setOverlayEnabled(enabled);
    }

    private boolean isFromGamepad(InputDevice device, int source) {
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD
                || (device != null && device.supportsSource(InputDevice.SOURCE_GAMEPAD));
    }

    private void ensureJoystickConnected() {
        if (sentJoystickConnected) {
            return;
        }
        InputNativeInterface.sendJoystickConnected();
        sentJoystickConnected = true;
    }

    private void sendMappedButton(GLFWBinding binding, boolean isPressed) {
        if (binding == GLFWBinding.GAMEPAD_LTRIGGER) {
            InputNativeInterface.sendJoystickAxis(GLFWBinding.GAMEPAD_AXIS_LT.code, isPressed ? 1f : 0f);
            return;
        }
        if (binding == GLFWBinding.GAMEPAD_RTRIGGER) {
            InputNativeInterface.sendJoystickAxis(GLFWBinding.GAMEPAD_AXIS_RT.code, isPressed ? 1f : 0f);
            return;
        }
        InputNativeInterface.sendJoystickButton(binding.code, isPressed);
    }

    private void sendMappedAxis(GLFWBinding targetAxis, float value) {
        InputNativeInterface.sendJoystickAxis(targetAxis.code, value);
    }

    private GLFWBinding getMappedButtonForKeyCode(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A:
                return externalControllerConfig.buttonA;
            case KeyEvent.KEYCODE_BUTTON_B:
                return externalControllerConfig.buttonB;
            case KeyEvent.KEYCODE_BUTTON_X:
                return externalControllerConfig.buttonX;
            case KeyEvent.KEYCODE_BUTTON_Y:
                return externalControllerConfig.buttonY;
            case KeyEvent.KEYCODE_BUTTON_L1:
                return externalControllerConfig.buttonLb;
            case KeyEvent.KEYCODE_BUTTON_R1:
                return externalControllerConfig.buttonRb;
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                return externalControllerConfig.buttonBack;
            case KeyEvent.KEYCODE_BUTTON_START:
                return externalControllerConfig.buttonStart;
            case KeyEvent.KEYCODE_BUTTON_THUMBL:
                return externalControllerConfig.buttonLStick;
            case KeyEvent.KEYCODE_BUTTON_THUMBR:
                return externalControllerConfig.buttonRStick;
            default:
                return null;
        }
    }

    private boolean handleDpadKey(int keyCode, boolean pressed) {
        int bit;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                bit = 0x1;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                bit = 0x2;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                bit = 0x4;
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                bit = 0x8;
                break;
            default:
                return false;
        }

        if (pressed) {
            dpadState |= bit;
        } else {
            dpadState &= ~bit;
        }
        InputNativeInterface.sendJoystickDpad(0, (char) dpadState);
        return true;
    }

    private float applyDeadZone(float value) {
        float deadZone = Math.clamp(externalControllerConfig.axisDeadZone, 0f, 0.95f);
        if (Math.abs(value) < deadZone) {
            return 0f;
        }
        return value;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        InputDevice device = event.getDevice();
        if (!isFromGamepad(device, event.getSource())) {
            return super.dispatchKeyEvent(event);
        }

        if (!externalControllerConfig.enabled) {
            return super.dispatchKeyEvent(event);
        }

        int action = event.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) {
            return super.dispatchKeyEvent(event);
        }

        if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() > 0) {
            return true;
        }

        ensureJoystickConnected();

        if (handleDpadKey(event.getKeyCode(), action == KeyEvent.ACTION_DOWN)) {
            return true;
        }

        GLFWBinding binding = getMappedButtonForKeyCode(event.getKeyCode());
        if (binding != null) {
            sendMappedButton(binding, action == KeyEvent.ACTION_DOWN);
            return true;
        }

        if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_L2) {
            sendMappedAxis(externalControllerConfig.axisLeftTrigger, action == KeyEvent.ACTION_DOWN ? 1f : 0f);
            return true;
        }

        if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_R2) {
            sendMappedAxis(externalControllerConfig.axisRightTrigger, action == KeyEvent.ACTION_DOWN ? 1f : 0f);
            return true;
        }

        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        InputDevice device = event.getDevice();
        if (!isFromGamepad(device, event.getSource())) {
            return super.onGenericMotionEvent(event);
        }

        if (!externalControllerConfig.enabled || event.getAction() != MotionEvent.ACTION_MOVE) {
            return super.onGenericMotionEvent(event);
        }

        ensureJoystickConnected();

        float leftX = applyDeadZone(event.getAxisValue(MotionEvent.AXIS_X));
        float leftY = applyDeadZone(event.getAxisValue(MotionEvent.AXIS_Y));
        float rightX = applyDeadZone(event.getAxisValue(MotionEvent.AXIS_Z));
        float rightY = applyDeadZone(event.getAxisValue(MotionEvent.AXIS_RZ));

        float leftTrigger = Math.max(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), 0f);
        float rightTrigger = Math.max(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), 0f);

        sendMappedAxis(externalControllerConfig.axisLeftX, leftX);
        sendMappedAxis(externalControllerConfig.axisLeftY, leftY);
        sendMappedAxis(externalControllerConfig.axisRightX, rightX);
        sendMappedAxis(externalControllerConfig.axisRightY, rightY);
        sendMappedAxis(externalControllerConfig.axisLeftTrigger, applyDeadZone(leftTrigger));
        sendMappedAxis(externalControllerConfig.axisRightTrigger, applyDeadZone(rightTrigger));
        return true;
    }
}
