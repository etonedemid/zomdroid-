package com.zomdroid.input;

import android.content.Context;
import android.hardware.input.InputManager;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

public class GamepadManager implements InputManager.InputDeviceListener {

    private final InputManager inputManager;
    private final GamepadListener listener;

    private static boolean touchOverride = false;

    public static final int GAMEPAD_BUTTON_COUNT = 11;

    private static final int STORE_IDX_LT = 15;
    private static final int STORE_IDX_RT = 16;

    private static final int TYPE_AXIS   = 0x01;
    private static final int TYPE_BUTTON = 0x02;
    private static final int TYPE_MASK   = 0xFF000000;
    private static final int VALUE_MASK  = 0x00FFFFFF;

    private static final int AXIS_LT_INDEX = 4;
    private static final int AXIS_RT_INDEX = 5;

    private static final int[] DEFAULT_MAPPING = {
        KeyEvent.KEYCODE_BUTTON_A,
        KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_BUTTON_X,
        KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_BUTTON_L1,
        KeyEvent.KEYCODE_BUTTON_R1,
        KeyEvent.KEYCODE_BUTTON_SELECT,
        KeyEvent.KEYCODE_BUTTON_START,
        KeyEvent.KEYCODE_BUTTON_MODE,
        KeyEvent.KEYCODE_BUTTON_THUMBL,
        KeyEvent.KEYCODE_BUTTON_THUMBR
    };

    private static int[] customMapping = null;

    private static final String PREFS_NAME = "gamepad_prefs";
    private static final String PREFS_KEY_MAPPING = "custom_gamepad_mapping";

    public interface GamepadListener {
        void onGamepadConnected();
        void onGamepadDisconnected();
        void onGamepadButton(int button, boolean pressed);
        void onGamepadAxis(int axis, float value);
        void onGamepadDpad(int dpad, char state);
    }

    public GamepadManager(Context context, GamepadListener listener) {
        this.inputManager = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
        this.listener = listener;
    }

    public static void setCustomMapping(int[] mapping, Context context) {
        if (mapping != null && mapping.length >= GAMEPAD_BUTTON_COUNT) {
            customMapping = java.util.Arrays.copyOf(mapping, mapping.length);
            saveCustomMapping(context);
        } else {
            customMapping = null;
            clearCustomMapping(context);
        }
    }

    public static int[] getCurrentMapping() {
        return (customMapping != null) ? customMapping : DEFAULT_MAPPING;
    }

    public static void setTouchOverride(boolean override) {
        touchOverride = override;
    }

    public static boolean isTouchOverrideEnabled() {
        return touchOverride;
    }

    public static void loadCustomMapping(Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String csv = prefs.getString(PREFS_KEY_MAPPING, null);
        if (csv != null && !csv.isEmpty()) {
            String[] parts = csv.split(",");
            if (parts.length >= GAMEPAD_BUTTON_COUNT) {
                int[] loaded = new int[parts.length];
                try {
                    for (int i = 0; i < parts.length; i++) {
                        loaded[i] = Integer.parseInt(parts[i]);
                    }
                    customMapping = loaded;
                } catch (NumberFormatException e) {
                    customMapping = null;
                }
            }
        }
    }

    private static void saveCustomMapping(Context context) {
        if (customMapping == null) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < customMapping.length; i++) {
            sb.append(customMapping[i]);
            if (i < customMapping.length - 1) sb.append(",");
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(PREFS_KEY_MAPPING, sb.toString()).apply();
    }

    private static void clearCustomMapping(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(PREFS_KEY_MAPPING).apply();
    }

    public void register() {
        inputManager.registerInputDeviceListener(this, null);
        for (int id : inputManager.getInputDeviceIds()) {
            InputDevice dev = inputManager.getInputDevice(id);
            if (dev != null && isGamepadDevice(dev)) {
                listener.onGamepadConnected();
                break;
            }
        }
    }

    public void unregister() {
        inputManager.unregisterInputDeviceListener(this);
    }

    public static boolean isGamepadDevice(InputDevice device) {
        if (isTouchOverrideEnabled()) return false;
        if (device == null) return false;

        int sources = device.getSources();
        boolean isGamepadSource = ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)
                || ((sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)
                || ((sources & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD);

        boolean hasMotion = device.getMotionRanges() != null && !device.getMotionRanges().isEmpty();

        return isGamepadSource && hasMotion;
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        InputDevice dev = inputManager.getInputDevice(deviceId);
        if (dev != null && isGamepadDevice(dev)) {
            listener.onGamepadConnected();
        }
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        int[] deviceIds = inputManager.getInputDeviceIds();
        boolean anyGamepad = false;
        for (int id : deviceIds) {
            InputDevice dev = inputManager.getInputDevice(id);
            if (dev != null && isGamepadDevice(dev)) {
                anyGamepad = true;
                break;
            }
        }
        if (!anyGamepad) {
            listener.onGamepadDisconnected();
        }
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {}

    public boolean isGamepadEvent(KeyEvent event) {
        int source = event.getSource();
        return ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)
                || ((source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)
                || ((source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD);
    }

    public boolean isGamepadMotionEvent(MotionEvent event) {
        int source = event.getSource();
        return ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)
                || ((source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK);
    }

    public boolean handleKeyEvent(KeyEvent event) {
        if (!isGamepadEvent(event)) return false;

        int keyCode = event.getKeyCode();
        boolean isPressed = event.getAction() == KeyEvent.ACTION_DOWN;

        if (isTriggerButton(keyCode, true)) {
            listener.onGamepadAxis(AXIS_LT_INDEX, isPressed ? 1.0f : 0.0f);
            return true;
        }
        if (isTriggerButton(keyCode, false)) {
            listener.onGamepadAxis(AXIS_RT_INDEX, isPressed ? 1.0f : 0.0f);
            return true;
        }

        int button = mapKeyCodeToGLFWButton(keyCode);
        if (button >= 0) {
            listener.onGamepadButton(button, isPressed);
            return true;
        }
        return false;
    }

    public boolean handleMotionEvent(MotionEvent event) {
        if (!isGamepadMotionEvent(event)) return false;

        float lx = event.getAxisValue(MotionEvent.AXIS_X);
        float ly = event.getAxisValue(MotionEvent.AXIS_Y);
        float rx = event.getAxisValue(MotionEvent.AXIS_Z);
        float ry = event.getAxisValue(MotionEvent.AXIS_RZ);
        listener.onGamepadAxis(0, lx);
        listener.onGamepadAxis(1, ly);
        listener.onGamepadAxis(2, rx);
        listener.onGamepadAxis(3, ry);

        if (isTriggerAxisMode(true)) {
            float lt = readTriggerAxis(event, true);
            listener.onGamepadAxis(AXIS_LT_INDEX, clamp01(lt));
        }
        if (isTriggerAxisMode(false)) {
            float rt = readTriggerAxis(event, false);
            listener.onGamepadAxis(AXIS_RT_INDEX, clamp01(rt));
        }

        float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
        float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
        char dpadState = 0;
        if (hatY < -0.5f) dpadState |= 0x01; // up
        if (hatY > 0.5f)  dpadState |= 0x04; // down
        if (hatX < -0.5f) dpadState |= 0x08; // left
        if (hatX > 0.5f)  dpadState |= 0x02; // right
        listener.onGamepadDpad(0, dpadState);

        return true;
    }

    private int mapKeyCodeToGLFWButton(int keyCode) {
        int[] mapping = getCurrentMapping();
        int len = Math.min(mapping.length, GAMEPAD_BUTTON_COUNT);
        for (int i = 0; i < len; i++) {
            if (mapping[i] == keyCode) return i;
        }
        return -1;
    }

    private boolean isTriggerButton(int keyCode, boolean left) {
        int code = getTriggerConfig(left);
        if (!isSentinel(code)) return false;
        int type = (code & TYPE_MASK) >>> 24;
        int val  = (code & VALUE_MASK);
        return (type == TYPE_BUTTON) && (val == keyCode);
    }

    private boolean isTriggerAxisMode(boolean left) {
        int code = getTriggerConfig(left);
        if (!isSentinel(code)) return true;
        int type = (code & TYPE_MASK) >>> 24;
        return type == TYPE_AXIS;
    }

    private int getTriggerConfig(boolean left) {
        int[] m = getCurrentMapping();
        int idx = left ? STORE_IDX_LT : STORE_IDX_RT;
        if (m == DEFAULT_MAPPING) return 0;
        if (m != null && idx < m.length) return m[idx];
        return 0;
    }

    private boolean isSentinel(int code) {
        int type = (code & TYPE_MASK) >>> 24;
        return (type == TYPE_AXIS) || (type == TYPE_BUTTON);
    }

    private float readTriggerAxis(MotionEvent ev, boolean left) {
        InputDevice d = ev.getDevice();
        if (d == null) return 0f;

        final int[] axes = left
                ? new int[]{
                MotionEvent.AXIS_LTRIGGER,
                MotionEvent.AXIS_BRAKE,
                MotionEvent.AXIS_Z,
                MotionEvent.AXIS_GENERIC_1,
                MotionEvent.AXIS_GENERIC_3,
                MotionEvent.AXIS_RX
        }
                : new int[]{
                MotionEvent.AXIS_RTRIGGER,
                MotionEvent.AXIS_GAS,
                MotionEvent.AXIS_RZ,
                MotionEvent.AXIS_GENERIC_2,
                MotionEvent.AXIS_GENERIC_4,
                MotionEvent.AXIS_RY
        };

        final int src = ev.getSource();
        for (int ax : axes) {
            if (d.getMotionRange(ax, src) != null || d.getMotionRange(ax) != null) {
                float v = ev.getAxisValue(ax);
                if (v < 0f) v = -v;
                if (v > 1f) v = 1f;
                return v;
            }
        }
        return 0f;
    }

    private float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }
}
