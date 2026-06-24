package com.zomdroid.input;

import android.content.Context;
import android.hardware.input.InputManager;
import android.view.InputDevice;
import android.view.KeyEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class KeyboardManager implements InputManager.InputDeviceListener {

    private final InputManager inputManager;
    private final KeyboardListener listener;
    private static boolean touchOverride = false;
    private final Context context;
    private final Set<DevKey> quarantined = new HashSet<>();
    private final Set<DevKey> goodKeyboards = new HashSet<>();
    private final Map<Integer, DevKey> idToKey = new HashMap<>();
    private boolean lastKeyboardPresent = false;

    public interface KeyboardListener {
        void onKeyboardConnected();
        void onKeyboardDisconnected();
        void onKeyboardKey(int glfwCode, boolean pressed);
    }

    public KeyboardManager(Context context, KeyboardListener listener) {
        this.context = context.getApplicationContext();
        this.inputManager = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
        this.listener = listener;
    }

    public void register() {
        inputManager.registerInputDeviceListener(this, null);
        goodKeyboards.clear();
        quarantined.clear();
        idToKey.clear();

        for (int id : inputManager.getInputDeviceIds()) {
            InputDevice dev = inputManager.getInputDevice(id);
            if (dev != null && isPhysicalKeyboard(dev)) {
                DevKey dk = new DevKey(dev);
                goodKeyboards.add(dk);
                idToKey.put(id, dk);
            }
        }

        // Small trick: invert last state to guarantee the listener is notified on init
        boolean now = !goodKeyboards.isEmpty();
        lastKeyboardPresent = !now;
        notifyIfStateChanged();
    }

    public void unregister() {
        inputManager.unregisterInputDeviceListener(this);
    }

    public static void setTouchOverride(boolean override) {
        touchOverride = override;
    }

    public static boolean isTouchOverrideEnabled() {
        return touchOverride;
    }

    public boolean handleKeyEvent(KeyEvent event) {
        final int src = event.getSource();
        if ((src & InputDevice.SOURCE_KEYBOARD) == 0 && src != 0) return false;

        int androidCode = event.getKeyCode();
        boolean isPressed = event.getAction() == KeyEvent.ACTION_DOWN;

        GLFWBinding b = KeyCodes.fromAndroid(androidCode);
        if (b == null) return false;

        if (listener != null) {
            listener.onKeyboardKey(b.code, isPressed);
            if (isPressed) {
                int unicode = event.getUnicodeChar(event.getMetaState() &
                        (KeyEvent.META_SHIFT_ON | KeyEvent.META_ALT_ON));
                if (unicode > 0) {
                    InputNativeInterface.sendChar(unicode);
                }
            }
        }
        return true;
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        InputDevice d = inputManager.getInputDevice(deviceId);
        if (d == null) return;

        if (isPhysicalKeyboard(d)) {
            DevKey dk = new DevKey(d);
            idToKey.put(deviceId, dk);
            goodKeyboards.add(dk);
            notifyIfStateChanged();
        }
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        DevKey dk = idToKey.remove(deviceId);
        if (dk != null) {
            goodKeyboards.remove(dk);
            quarantined.remove(dk);
        }
        notifyIfStateChanged();
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
        InputDevice d = inputManager.getInputDevice(deviceId);
        if (d == null) {
            DevKey oldKey = idToKey.remove(deviceId);
            if (oldKey != null) {
                goodKeyboards.remove(oldKey);
                quarantined.remove(oldKey);
            }
            notifyIfStateChanged();
            return;
        }

        DevKey newKey = new DevKey(d);
        DevKey oldKey = idToKey.get(deviceId);

        if (oldKey != null && !oldKey.equals(newKey)) {
            goodKeyboards.remove(oldKey);
            quarantined.remove(oldKey);
        }

        if (isPhysicalKeyboard(d)) {
            idToKey.put(deviceId, newKey);
            quarantined.remove(newKey);
            goodKeyboards.add(newKey);
        } else {
            if (oldKey != null) {
                goodKeyboards.remove(oldKey);
                quarantined.remove(oldKey);
                idToKey.remove(deviceId);
            }
        }
        notifyIfStateChanged();
    }

    private boolean isPhysicalKeyboard(InputDevice device) {
        if (device == null) return false;
        if (isTouchOverrideEnabled()) return false;
        if (!device.supportsSource(InputDevice.SOURCE_KEYBOARD)) return false;

        int type = device.getKeyboardType();
        if (type != InputDevice.KEYBOARD_TYPE_ALPHABETIC
                && type != InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC) return false;

        String name = device.getName().toLowerCase();
        if (name.contains("mouse") || name.contains("touch") || name.contains("touchpad")
                || name.contains("remote") || name.contains("gamepad")
                || name.contains("controller")) return false;

        try {
            boolean[] out = device.hasKeys(
                    KeyEvent.KEYCODE_A,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_SPACE
            );
            int hits = 0;
            for (boolean b : out) if (b) hits++;
            return hits >= 2;
        } catch (Throwable t) {
            return false;
        }
    }

    private void notifyIfStateChanged() {
        boolean nowPresent = !goodKeyboards.isEmpty();
        if (nowPresent == lastKeyboardPresent) return;
        lastKeyboardPresent = nowPresent;
        if (nowPresent) listener.onKeyboardConnected();
        else listener.onKeyboardDisconnected();
    }

    private static class DevKey {
        final int id;
        final String name;

        DevKey(InputDevice dev) {
            this.id = dev.getId();
            this.name = dev.getName();
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof DevKey)) return false;
            DevKey d = (DevKey) o;
            return id == d.id && Objects.equals(name, d.name);
        }

        @Override
        public int hashCode() {
            return 31 * id + (name != null ? name.hashCode() : 0);
        }
    }
}
