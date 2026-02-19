package com.zomdroid.fragments;

import android.os.Bundle;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.zomdroid.R;
import com.zomdroid.databinding.ElementControllerMappingRowBinding;
import com.zomdroid.databinding.FragmentControllerConfigBinding;
import com.zomdroid.input.ExternalControllerConfig;
import com.zomdroid.input.GLFWBinding;

public class ControllerConfigFragment extends Fragment {
    private static final float AXIS_CAPTURE_THRESHOLD = 0.6f;

    private FragmentControllerConfigBinding binding;
    private ExternalControllerConfig config;

    interface BindingAccessor {
        GLFWBinding get();
        void set(GLFWBinding value);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentControllerConfigBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        config = ExternalControllerConfig.load(requireContext());

        // General settings
        binding.controllerConfigEnabledMs.setChecked(config.enabled);
        binding.controllerConfigEnabledMs.setOnCheckedChangeListener((buttonView, isChecked) -> {
            config.enabled = isChecked;
            config.save();
        });

        binding.controllerConfigOverlayControlsMs.setChecked(config.overlayControlsEnabled);
        binding.controllerConfigOverlayControlsMs.setOnCheckedChangeListener((buttonView, isChecked) -> {
            config.overlayControlsEnabled = isChecked;
            config.save();
        });

        binding.controllerConfigDeadzoneSb.setProgress((int) (config.axisDeadZone * 100f));
        updateDeadZoneText();
        binding.controllerConfigDeadzoneSb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                config.axisDeadZone = Math.clamp(progress / 100f, 0f, 0.95f);
                updateDeadZoneText();
                config.save();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Mappings
        addButtonRows();
        addAxisRows();

        // Reset
        binding.controllerConfigResetMb.setOnClickListener(v -> {
            config.resetToDefaults();
            binding.controllerConfigButtonMappingsContainer.removeAllViews();
            binding.controllerConfigAxisMappingsContainer.removeAllViews();
            binding.controllerConfigEnabledMs.setChecked(config.enabled);
            binding.controllerConfigOverlayControlsMs.setChecked(config.overlayControlsEnabled);
            binding.controllerConfigDeadzoneSb.setProgress((int) (config.axisDeadZone * 100f));
            updateDeadZoneText();
            addButtonRows();
            addAxisRows();
        });
    }

    private void updateDeadZoneText() {
        binding.controllerConfigDeadzoneValueTv.setText(
                getString(R.string.percentage_format, (int) (config.axisDeadZone * 100f)));
    }

    // ── Button Rows ──────────────────────────────────────────────────────────────

    private void addButtonRows() {
        LinearLayout c = binding.controllerConfigButtonMappingsContainer;
        GLFWBinding[] opts = ExternalControllerConfig.buttonOptions();

        addRow(c, R.string.controller_config_physical_a, opts, false,
                new A() { public GLFWBinding get() { return config.buttonA; } public void set(GLFWBinding v) { config.buttonA = v; } });
        addRow(c, R.string.controller_config_physical_b, opts, false,
                new A() { public GLFWBinding get() { return config.buttonB; } public void set(GLFWBinding v) { config.buttonB = v; } });
        addRow(c, R.string.controller_config_physical_x, opts, false,
                new A() { public GLFWBinding get() { return config.buttonX; } public void set(GLFWBinding v) { config.buttonX = v; } });
        addRow(c, R.string.controller_config_physical_y, opts, false,
                new A() { public GLFWBinding get() { return config.buttonY; } public void set(GLFWBinding v) { config.buttonY = v; } });
        addRow(c, R.string.controller_config_physical_lb, opts, false,
                new A() { public GLFWBinding get() { return config.buttonLb; } public void set(GLFWBinding v) { config.buttonLb = v; } });
        addRow(c, R.string.controller_config_physical_rb, opts, false,
                new A() { public GLFWBinding get() { return config.buttonRb; } public void set(GLFWBinding v) { config.buttonRb = v; } });
        addRow(c, R.string.controller_config_physical_back, opts, false,
                new A() { public GLFWBinding get() { return config.buttonBack; } public void set(GLFWBinding v) { config.buttonBack = v; } });
        addRow(c, R.string.controller_config_physical_start, opts, false,
                new A() { public GLFWBinding get() { return config.buttonStart; } public void set(GLFWBinding v) { config.buttonStart = v; } });
        addRow(c, R.string.controller_config_physical_l3, opts, false,
                new A() { public GLFWBinding get() { return config.buttonLStick; } public void set(GLFWBinding v) { config.buttonLStick = v; } });
        addRow(c, R.string.controller_config_physical_r3, opts, false,
                new A() { public GLFWBinding get() { return config.buttonRStick; } public void set(GLFWBinding v) { config.buttonRStick = v; } });
        addRow(c, R.string.controller_config_physical_dpad_up, opts, false,
                new A() { public GLFWBinding get() { return config.buttonDpadUp; } public void set(GLFWBinding v) { config.buttonDpadUp = v; } });
        addRow(c, R.string.controller_config_physical_dpad_down, opts, false,
                new A() { public GLFWBinding get() { return config.buttonDpadDown; } public void set(GLFWBinding v) { config.buttonDpadDown = v; } });
        addRow(c, R.string.controller_config_physical_dpad_left, opts, false,
                new A() { public GLFWBinding get() { return config.buttonDpadLeft; } public void set(GLFWBinding v) { config.buttonDpadLeft = v; } });
        addRow(c, R.string.controller_config_physical_dpad_right, opts, false,
                new A() { public GLFWBinding get() { return config.buttonDpadRight; } public void set(GLFWBinding v) { config.buttonDpadRight = v; } });
    }

    // ── Axis Rows ────────────────────────────────────────────────────────────────

    private void addAxisRows() {
        LinearLayout c = binding.controllerConfigAxisMappingsContainer;
        GLFWBinding[] opts = ExternalControllerConfig.axisOptions();

        addRow(c, R.string.controller_config_axis_left_x, opts, true,
                new A() { public GLFWBinding get() { return config.axisLeftX; } public void set(GLFWBinding v) { config.axisLeftX = v; } });
        addRow(c, R.string.controller_config_axis_left_y, opts, true,
                new A() { public GLFWBinding get() { return config.axisLeftY; } public void set(GLFWBinding v) { config.axisLeftY = v; } });
        addRow(c, R.string.controller_config_axis_right_x, opts, true,
                new A() { public GLFWBinding get() { return config.axisRightX; } public void set(GLFWBinding v) { config.axisRightX = v; } });
        addRow(c, R.string.controller_config_axis_right_y, opts, true,
                new A() { public GLFWBinding get() { return config.axisRightY; } public void set(GLFWBinding v) { config.axisRightY = v; } });
        addRow(c, R.string.controller_config_axis_left_trigger, opts, true,
                new A() { public GLFWBinding get() { return config.axisLeftTrigger; } public void set(GLFWBinding v) { config.axisLeftTrigger = v; } });
        addRow(c, R.string.controller_config_axis_right_trigger, opts, true,
                new A() { public GLFWBinding get() { return config.axisRightTrigger; } public void set(GLFWBinding v) { config.axisRightTrigger = v; } });
    }

    // Shorthand alias for anonymous accessor classes
    private static abstract class A implements BindingAccessor {}

    // ── Row creation ─────────────────────────────────────────────────────────────

    private void addRow(LinearLayout parent, int labelRes, GLFWBinding[] options,
                        boolean axisCapture, BindingAccessor accessor) {
        ElementControllerMappingRowBinding row =
                ElementControllerMappingRowBinding.inflate(getLayoutInflater(), parent, true);
        row.controllerMappingLabelTv.setText(labelRes);
        row.controllerMappingValueMb.setText(formatBindingName(accessor.get()));
        row.controllerMappingValueMb.setOnClickListener(v ->
                showCaptureDialog(labelRes, options, axisCapture, accessor, row.controllerMappingValueMb));
    }

    // ── Capture Dialog ───────────────────────────────────────────────────────────
    //
    // Uses a MaterialAlertDialog with setOnKeyListener for button capture and
    // DecorView.setOnGenericMotionListener for axis / D-pad HAT capture.
    // This avoids the unreliable root-layout key/motion listeners.

    private void showCaptureDialog(int labelRes, GLFWBinding[] options, boolean axisCapture,
                                   BindingAccessor accessor, MaterialButton valueButton) {
        String label = getString(labelRes);

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.controller_config_capture_title, label))
                .setMessage(axisCapture
                        ? getString(R.string.controller_config_capture_axis_instruction)
                        : getString(R.string.controller_config_capture_button_instruction))
                .setNegativeButton(R.string.dialog_button_cancel, null)
                .create();

        // Key events: handle gamepad buttons and D-pad key codes
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK) return false; // allow dismiss

            if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() > 0) return true;
            if (!isFromGamepad(event)) return false;

            GLFWBinding detected = detectBindingFromKeyCode(keyCode);
            if (detected != null && containsBinding(options, detected)) {
                applyBinding(accessor, detected, valueButton);
                dialog.dismiss();
                return true;
            }
            return true; // consume unrecognised gamepad keys
        });

        dialog.show();

        // Motion events: handle analog axes, triggers, and D-pad HAT
        if (dialog.getWindow() != null) {
            View decor = dialog.getWindow().getDecorView();
            decor.setFocusable(true);
            decor.setFocusableInTouchMode(true);
            decor.requestFocus();
            decor.setOnGenericMotionListener((v, event) -> {
                if (!isJoystickEvent(event) || event.getAction() != MotionEvent.ACTION_MOVE) {
                    return false;
                }

                GLFWBinding detected = null;
                if (axisCapture) {
                    detected = detectAxisBindingFromMotionEvent(event);
                } else {
                    // For button capture: also detect D-pad from HAT axes and triggers
                    detected = detectDpadFromMotionEvent(event);
                    if (detected == null) {
                        detected = detectTriggerButtonBindingFromMotionEvent(event);
                    }
                }

                if (detected != null && containsBinding(options, detected)) {
                    applyBinding(accessor, detected, valueButton);
                    dialog.dismiss();
                    return true;
                }
                return true; // consume all joystick motion while dialog is open
            });
        }
    }

    private void applyBinding(BindingAccessor accessor, GLFWBinding detected, MaterialButton btn) {
        accessor.set(detected);
        config.save();
        btn.setText(formatBindingName(detected));
    }

    // ── Input detection helpers ──────────────────────────────────────────────────

    private boolean isFromGamepad(KeyEvent event) {
        InputDevice device = event.getDevice();
        int source = event.getSource();
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD
                || (device != null && device.supportsSource(InputDevice.SOURCE_GAMEPAD));
    }

    private boolean isJoystickEvent(MotionEvent event) {
        return (event.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (event.getSource() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
    }

    private GLFWBinding detectBindingFromKeyCode(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A:     return GLFWBinding.GAMEPAD_BUTTON_A;
            case KeyEvent.KEYCODE_BUTTON_B:     return GLFWBinding.GAMEPAD_BUTTON_B;
            case KeyEvent.KEYCODE_BUTTON_X:     return GLFWBinding.GAMEPAD_BUTTON_X;
            case KeyEvent.KEYCODE_BUTTON_Y:     return GLFWBinding.GAMEPAD_BUTTON_Y;
            case KeyEvent.KEYCODE_BUTTON_L1:    return GLFWBinding.GAMEPAD_BUTTON_LB;
            case KeyEvent.KEYCODE_BUTTON_R1:    return GLFWBinding.GAMEPAD_BUTTON_RB;
            case KeyEvent.KEYCODE_BUTTON_SELECT:return GLFWBinding.GAMEPAD_BUTTON_BACK;
            case KeyEvent.KEYCODE_BUTTON_START: return GLFWBinding.GAMEPAD_BUTTON_START;
            case KeyEvent.KEYCODE_BUTTON_MODE:  return GLFWBinding.GAMEPAD_BUTTON_GUIDE;
            case KeyEvent.KEYCODE_BUTTON_THUMBL:return GLFWBinding.GAMEPAD_BUTTON_LSTICK;
            case KeyEvent.KEYCODE_BUTTON_THUMBR:return GLFWBinding.GAMEPAD_BUTTON_RSTICK;
            case KeyEvent.KEYCODE_BUTTON_L2:    return GLFWBinding.GAMEPAD_LTRIGGER;
            case KeyEvent.KEYCODE_BUTTON_R2:    return GLFWBinding.GAMEPAD_RTRIGGER;
            case KeyEvent.KEYCODE_DPAD_UP:      return GLFWBinding.GAMEPAD_BUTTON_DPAD_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN:    return GLFWBinding.GAMEPAD_BUTTON_DPAD_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT:    return GLFWBinding.GAMEPAD_BUTTON_DPAD_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT:   return GLFWBinding.GAMEPAD_BUTTON_DPAD_RIGHT;
            default:                            return null;
        }
    }

    /** Detect D-pad from analog HAT axes (many controllers use this instead of key events). */
    private GLFWBinding detectDpadFromMotionEvent(MotionEvent event) {
        float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
        float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);

        if (hatX > 0.5f)  return GLFWBinding.GAMEPAD_BUTTON_DPAD_RIGHT;
        if (hatX < -0.5f) return GLFWBinding.GAMEPAD_BUTTON_DPAD_LEFT;
        if (hatY > 0.5f)  return GLFWBinding.GAMEPAD_BUTTON_DPAD_DOWN;
        if (hatY < -0.5f) return GLFWBinding.GAMEPAD_BUTTON_DPAD_UP;

        return null;
    }

    /** Detect stick / trigger axes (for axis capture mode). */
    private GLFWBinding detectAxisBindingFromMotionEvent(MotionEvent event) {
        GLFWBinding detected = null;
        float max = AXIS_CAPTURE_THRESHOLD;

        float leftX = Math.abs(event.getAxisValue(MotionEvent.AXIS_X));
        if (leftX > max) { max = leftX; detected = GLFWBinding.GAMEPAD_AXIS_LX; }

        float leftY = Math.abs(event.getAxisValue(MotionEvent.AXIS_Y));
        if (leftY > max) { max = leftY; detected = GLFWBinding.GAMEPAD_AXIS_LY; }

        float rightX = Math.abs(event.getAxisValue(MotionEvent.AXIS_Z));
        if (rightX > max) { max = rightX; detected = GLFWBinding.GAMEPAD_AXIS_RX; }

        float rightY = Math.abs(event.getAxisValue(MotionEvent.AXIS_RZ));
        if (rightY > max) { max = rightY; detected = GLFWBinding.GAMEPAD_AXIS_RY; }

        float lt = Math.max(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), 0f);
        if (lt > max) { max = lt; detected = GLFWBinding.GAMEPAD_AXIS_LT; }

        float rt = Math.max(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), 0f);
        if (rt > max) { detected = GLFWBinding.GAMEPAD_AXIS_RT; }

        return detected;
    }

    /** Detect trigger presses as button bindings (for button capture mode). */
    private GLFWBinding detectTriggerButtonBindingFromMotionEvent(MotionEvent event) {
        float lt = Math.max(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), 0f);
        float rt = Math.max(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), 0f);

        if (lt >= AXIS_CAPTURE_THRESHOLD && lt >= rt) return GLFWBinding.GAMEPAD_LTRIGGER;
        if (rt >= AXIS_CAPTURE_THRESHOLD)             return GLFWBinding.GAMEPAD_RTRIGGER;
        return null;
    }

    // ── Formatting helpers ───────────────────────────────────────────────────────

    private boolean containsBinding(GLFWBinding[] options, GLFWBinding candidate) {
        for (GLFWBinding option : options) {
            if (option == candidate) return true;
        }
        return false;
    }

    private String formatBindingName(GLFWBinding b) {
        String v = b.name();
        if (v.startsWith("GAMEPAD_BUTTON_")) v = v.substring("GAMEPAD_BUTTON_".length());
        else if (v.startsWith("GAMEPAD_AXIS_")) v = v.substring("GAMEPAD_AXIS_".length());
        else if (v.startsWith("GAMEPAD_"))      v = v.substring("GAMEPAD_".length());
        return v.replace('_', ' ');
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
