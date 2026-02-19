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
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.zomdroid.R;
import com.zomdroid.databinding.ElementControllerMappingRowBinding;
import com.zomdroid.databinding.FragmentControllerConfigBinding;
import com.zomdroid.input.ExternalControllerConfig;
import com.zomdroid.input.GLFWBinding;

public class ControllerConfigFragment extends Fragment {
    private static final float AXIS_CAPTURE_THRESHOLD = 0.6f;

    private static class BindingCaptureSession {
        final int labelRes;
        final GLFWBinding[] options;
        final BindingAccessor accessor;
        final MaterialButton valueButton;
        final boolean axisCapture;

        BindingCaptureSession(int labelRes, GLFWBinding[] options, BindingAccessor accessor,
                              MaterialButton valueButton, boolean axisCapture) {
            this.labelRes = labelRes;
            this.options = options;
            this.accessor = accessor;
            this.valueButton = valueButton;
            this.axisCapture = axisCapture;
        }
    }

    private FragmentControllerConfigBinding binding;
    private ExternalControllerConfig config;
    private BindingCaptureSession activeCapture;

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

        binding.controllerConfigEnabledMs.setChecked(config.enabled);
        binding.controllerConfigEnabledMs.setOnCheckedChangeListener((buttonView, isChecked) -> {
            config.enabled = isChecked;
            config.save();
        });

        // Overlay controls toggle for external controller config
        // This lets users disable in-game overlay controls when using an external controller
        // Add and wire the switch if the view exists in layout (we'll add it to XML next)
        try {
            binding.controllerConfigOverlayControlsMs.setChecked(config.overlayControlsEnabled);
            binding.controllerConfigOverlayControlsMs.setOnCheckedChangeListener((buttonView, isChecked) -> {
                config.overlayControlsEnabled = isChecked;
                config.save();
            });
        } catch (Exception ignored) {
            // If the view isn't present (older layout), ignore and continue
        }

        binding.controllerConfigDeadzoneSb.setProgress((int) (config.axisDeadZone * 100f));
        updateDeadZoneText();
        binding.controllerConfigDeadzoneSb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                config.axisDeadZone = Math.clamp(progress / 100f, 0f, 0.95f);
                updateDeadZoneText();
                config.save();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        addButtonRows();
        addAxisRows();

        binding.controllerConfigResetMb.setOnClickListener(v -> {
            activeCapture = null;
            config.resetToDefaults();
            binding.controllerConfigButtonMappingsContainer.removeAllViews();
            binding.controllerConfigAxisMappingsContainer.removeAllViews();
            binding.controllerConfigEnabledMs.setChecked(config.enabled);
            binding.controllerConfigDeadzoneSb.setProgress((int) (config.axisDeadZone * 100f));
            updateDeadZoneText();
            addButtonRows();
            addAxisRows();
        });

        binding.controllerConfigRootLl.requestFocus();
        binding.controllerConfigRootLl.setOnKeyListener((v, keyCode, event) -> {
            if (!isFromGamepad(event)) {
                return false;
            }

            if (event.getAction() == KeyEvent.ACTION_DOWN || event.getAction() == KeyEvent.ACTION_UP) {
                binding.controllerConfigPreviewTv.setText(getString(
                        R.string.controller_config_preview_button,
                        KeyEvent.keyCodeToString(keyCode),
                        event.getAction() == KeyEvent.ACTION_DOWN ? getString(R.string.controller_config_state_down) : getString(R.string.controller_config_state_up)
                ));
            }

            if (activeCapture != null && event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                GLFWBinding detected = detectBindingFromKeyCode(keyCode);
                if (detected != null) {
                    applyCapturedBinding(detected);
                }
            }

            return true;
        });

        binding.controllerConfigRootLl.setOnGenericMotionListener((v, event) -> {
            if (!isJoystickEvent(event)) {
                return false;
            }

            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                float leftX = event.getAxisValue(MotionEvent.AXIS_X);
                float leftY = event.getAxisValue(MotionEvent.AXIS_Y);
                float rightX = event.getAxisValue(MotionEvent.AXIS_Z);
                float rightY = event.getAxisValue(MotionEvent.AXIS_RZ);
                float leftTrigger = event.getAxisValue(MotionEvent.AXIS_LTRIGGER);
                float rightTrigger = event.getAxisValue(MotionEvent.AXIS_RTRIGGER);

                binding.controllerConfigPreviewTv.setText(getString(
                        R.string.controller_config_preview_axes,
                        leftX, leftY, rightX, rightY, leftTrigger, rightTrigger
                ));

                if (activeCapture != null) {
                    GLFWBinding detected = activeCapture.axisCapture
                            ? detectAxisBindingFromMotionEvent(event)
                            : detectTriggerButtonBindingFromMotionEvent(event);
                    if (detected != null) {
                        applyCapturedBinding(detected);
                    }
                }
            }

            return true;
        });
    }

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

    private void updateDeadZoneText() {
        binding.controllerConfigDeadzoneValueTv.setText(getString(R.string.percentage_format, (int) (config.axisDeadZone * 100f)));
    }

    private void addButtonRows() {
        addMappingRow(
                binding.controllerConfigButtonMappingsContainer,
                R.string.controller_config_physical_a,
                ExternalControllerConfig.buttonOptions(),
                false,
                new BindingAccessor() {
                    @Override
                    public GLFWBinding get() {
                        return config.buttonA;
                    }

                    @Override
                    public void set(GLFWBinding value) {
                        config.buttonA = value;
                    }
                }
        );
        addMappingRow(
                binding.controllerConfigButtonMappingsContainer,
                R.string.controller_config_physical_b,
                ExternalControllerConfig.buttonOptions(),
            false,
                new BindingAccessor() {
                    @Override
                    public GLFWBinding get() {
                        return config.buttonB;
                    }

                    @Override
                    public void set(GLFWBinding value) {
                        config.buttonB = value;
                    }
                }
        );
        addMappingRow(
                binding.controllerConfigButtonMappingsContainer,
                R.string.controller_config_physical_x,
                ExternalControllerConfig.buttonOptions(),
            false,
                new BindingAccessor() {
                    @Override
                    public GLFWBinding get() {
                        return config.buttonX;
                    }

                    @Override
                    public void set(GLFWBinding value) {
                        config.buttonX = value;
                    }
                }
        );
        addMappingRow(
                binding.controllerConfigButtonMappingsContainer,
                R.string.controller_config_physical_y,
                ExternalControllerConfig.buttonOptions(),
            false,
                new BindingAccessor() {
                    @Override
                    public GLFWBinding get() {
                        return config.buttonY;
                    }

                    @Override
                    public void set(GLFWBinding value) {
                        config.buttonY = value;
                    }
                }
        );
        addMappingRow(
                binding.controllerConfigButtonMappingsContainer,
                R.string.controller_config_physical_lb,
                ExternalControllerConfig.buttonOptions(),
            false,
                new BindingAccessor() {
                    @Override
                    public GLFWBinding get() {
                        return config.buttonLb;
                    }

                    @Override
                    public void set(GLFWBinding value) {
                        config.buttonLb = value;
                    }
                }
        );
        addMappingRow(
                binding.controllerConfigButtonMappingsContainer,
                R.string.controller_config_physical_rb,
                ExternalControllerConfig.buttonOptions(),
            false,
                new BindingAccessor() {
                    @Override
                    public GLFWBinding get() {
                        return config.buttonRb;
                    }

                    @Override
                    public void set(GLFWBinding value) {
                        config.buttonRb = value;
                    }
                }
        );
        addMappingRow(
                binding.controllerConfigButtonMappingsContainer,
                R.string.controller_config_physical_back,
                ExternalControllerConfig.buttonOptions(),
            false,
                new BindingAccessor() {
                    @Override
                    public GLFWBinding get() {
                        return config.buttonBack;
                    }

                    @Override
                    public void set(GLFWBinding value) {
                        config.buttonBack = value;
                    }
                }
        );
        addMappingRow(
                binding.controllerConfigButtonMappingsContainer,
                R.string.controller_config_physical_start,
                ExternalControllerConfig.buttonOptions(),
            false,
                new BindingAccessor() {
                    @Override
                    public GLFWBinding get() {
                        return config.buttonStart;
                    }

                    @Override
                    public void set(GLFWBinding value) {
                        config.buttonStart = value;
                    }
                }
        );
        addMappingRow(
                binding.controllerConfigButtonMappingsContainer,
                R.string.controller_config_physical_l3,
                ExternalControllerConfig.buttonOptions(),
            false,
                new BindingAccessor() {
                    @Override
                    public GLFWBinding get() {
                        return config.buttonLStick;
                    }

                    @Override
                    public void set(GLFWBinding value) {
                        config.buttonLStick = value;
                    }
                }
        );
        addMappingRow(
                binding.controllerConfigButtonMappingsContainer,
                R.string.controller_config_physical_r3,
                ExternalControllerConfig.buttonOptions(),
            false,
                new BindingAccessor() {
                    @Override
                    public GLFWBinding get() {
                        return config.buttonRStick;
                    }

                    @Override
                    public void set(GLFWBinding value) {
                        config.buttonRStick = value;
                    }
                }
        );
    }

    private void addAxisRows() {
        addMappingRow(
                binding.controllerConfigAxisMappingsContainer,
                R.string.controller_config_axis_left_x,
                ExternalControllerConfig.axisOptions(),
                true,
                new BindingAccessor() {
                    @Override
                    public GLFWBinding get() {
                        return config.axisLeftX;
                    }

                    @Override
                    public void set(GLFWBinding value) {
                        config.axisLeftX = value;
                    }
                }
        );
        addMappingRow(
                binding.controllerConfigAxisMappingsContainer,
                R.string.controller_config_axis_left_y,
                ExternalControllerConfig.axisOptions(),
            true,
                new BindingAccessor() {
                    @Override
                    public GLFWBinding get() {
                        return config.axisLeftY;
                    }

                    @Override
                    public void set(GLFWBinding value) {
                        config.axisLeftY = value;
                    }
                }
        );
        addMappingRow(
                binding.controllerConfigAxisMappingsContainer,
                R.string.controller_config_axis_right_x,
                ExternalControllerConfig.axisOptions(),
            true,
                new BindingAccessor() {
                    @Override
                    public GLFWBinding get() {
                        return config.axisRightX;
                    }

                    @Override
                    public void set(GLFWBinding value) {
                        config.axisRightX = value;
                    }
                }
        );
        addMappingRow(
                binding.controllerConfigAxisMappingsContainer,
                R.string.controller_config_axis_right_y,
                ExternalControllerConfig.axisOptions(),
            true,
                new BindingAccessor() {
                    @Override
                    public GLFWBinding get() {
                        return config.axisRightY;
                    }

                    @Override
                    public void set(GLFWBinding value) {
                        config.axisRightY = value;
                    }
                }
        );
        addMappingRow(
                binding.controllerConfigAxisMappingsContainer,
                R.string.controller_config_axis_left_trigger,
                ExternalControllerConfig.axisOptions(),
            true,
                new BindingAccessor() {
                    @Override
                    public GLFWBinding get() {
                        return config.axisLeftTrigger;
                    }

                    @Override
                    public void set(GLFWBinding value) {
                        config.axisLeftTrigger = value;
                    }
                }
        );
        addMappingRow(
                binding.controllerConfigAxisMappingsContainer,
                R.string.controller_config_axis_right_trigger,
                ExternalControllerConfig.axisOptions(),
            true,
                new BindingAccessor() {
                    @Override
                    public GLFWBinding get() {
                        return config.axisRightTrigger;
                    }

                    @Override
                    public void set(GLFWBinding value) {
                        config.axisRightTrigger = value;
                    }
                }
        );
    }

    private void addMappingRow(LinearLayout parent, int labelRes, GLFWBinding[] options,
                               boolean axisCapture, BindingAccessor accessor) {
        ElementControllerMappingRowBinding rowBinding = ElementControllerMappingRowBinding.inflate(getLayoutInflater(), parent, true);
        TextView labelTv = rowBinding.controllerMappingLabelTv;
        MaterialButton valueMb = rowBinding.controllerMappingValueMb;

        labelTv.setText(labelRes);
        valueMb.setText(formatBindingName(accessor.get()));
        valueMb.setOnClickListener(v -> startCapture(labelRes, options, axisCapture, accessor, valueMb));
    }

    private void startCapture(int labelRes, GLFWBinding[] options, boolean axisCapture,
                              BindingAccessor accessor, MaterialButton valueButton) {
        activeCapture = new BindingCaptureSession(labelRes, options, accessor, valueButton, axisCapture);
        binding.controllerConfigPreviewTv.setText(
                getString(R.string.controller_config_waiting_for_input, getString(labelRes))
        );
        binding.controllerConfigRootLl.requestFocus();
    }

    private void applyCapturedBinding(GLFWBinding detectedBinding) {
        if (activeCapture == null || !containsBinding(activeCapture.options, detectedBinding)) {
            return;
        }

        BindingCaptureSession capture = activeCapture;
        activeCapture = null;
        capture.accessor.set(detectedBinding);
        config.save();

        String mappedName = formatBindingName(detectedBinding);
        capture.valueButton.setText(mappedName);
        binding.controllerConfigPreviewTv.setText(
                getString(R.string.controller_config_mapped_to, getString(capture.labelRes), mappedName)
        );
    }

    private boolean containsBinding(GLFWBinding[] options, GLFWBinding candidate) {
        for (GLFWBinding option : options) {
            if (option == candidate) {
                return true;
            }
        }
        return false;
    }

    private String formatBindingName(GLFWBinding binding) {
        String value = binding.name();
        if (value.startsWith("GAMEPAD_BUTTON_")) {
            value = value.substring("GAMEPAD_BUTTON_".length());
        } else if (value.startsWith("GAMEPAD_AXIS_")) {
            value = value.substring("GAMEPAD_AXIS_".length());
        } else if (value.startsWith("GAMEPAD_")) {
            value = value.substring("GAMEPAD_".length());
        }
        return value.replace('_', ' ');
    }

    private GLFWBinding detectBindingFromKeyCode(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A:
                return GLFWBinding.GAMEPAD_BUTTON_A;
            case KeyEvent.KEYCODE_BUTTON_B:
                return GLFWBinding.GAMEPAD_BUTTON_B;
            case KeyEvent.KEYCODE_BUTTON_X:
                return GLFWBinding.GAMEPAD_BUTTON_X;
            case KeyEvent.KEYCODE_BUTTON_Y:
                return GLFWBinding.GAMEPAD_BUTTON_Y;
            case KeyEvent.KEYCODE_BUTTON_L1:
                return GLFWBinding.GAMEPAD_BUTTON_LB;
            case KeyEvent.KEYCODE_BUTTON_R1:
                return GLFWBinding.GAMEPAD_BUTTON_RB;
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                return GLFWBinding.GAMEPAD_BUTTON_BACK;
            case KeyEvent.KEYCODE_BUTTON_START:
                return GLFWBinding.GAMEPAD_BUTTON_START;
            case KeyEvent.KEYCODE_BUTTON_MODE:
                return GLFWBinding.GAMEPAD_BUTTON_GUIDE;
            case KeyEvent.KEYCODE_BUTTON_THUMBL:
                return GLFWBinding.GAMEPAD_BUTTON_LSTICK;
            case KeyEvent.KEYCODE_BUTTON_THUMBR:
                return GLFWBinding.GAMEPAD_BUTTON_RSTICK;
            case KeyEvent.KEYCODE_BUTTON_L2:
                return GLFWBinding.GAMEPAD_LTRIGGER;
            case KeyEvent.KEYCODE_BUTTON_R2:
                return GLFWBinding.GAMEPAD_RTRIGGER;
            default:
                return null;
        }
    }

    private GLFWBinding detectAxisBindingFromMotionEvent(MotionEvent event) {
        GLFWBinding detected = null;
        float max = AXIS_CAPTURE_THRESHOLD;

        float leftX = Math.abs(event.getAxisValue(MotionEvent.AXIS_X));
        if (leftX > max) {
            max = leftX;
            detected = GLFWBinding.GAMEPAD_AXIS_LX;
        }

        float leftY = Math.abs(event.getAxisValue(MotionEvent.AXIS_Y));
        if (leftY > max) {
            max = leftY;
            detected = GLFWBinding.GAMEPAD_AXIS_LY;
        }

        float rightX = Math.abs(event.getAxisValue(MotionEvent.AXIS_Z));
        if (rightX > max) {
            max = rightX;
            detected = GLFWBinding.GAMEPAD_AXIS_RX;
        }

        float rightY = Math.abs(event.getAxisValue(MotionEvent.AXIS_RZ));
        if (rightY > max) {
            max = rightY;
            detected = GLFWBinding.GAMEPAD_AXIS_RY;
        }

        float leftTrigger = Math.max(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), 0f);
        if (leftTrigger > max) {
            max = leftTrigger;
            detected = GLFWBinding.GAMEPAD_AXIS_LT;
        }

        float rightTrigger = Math.max(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), 0f);
        if (rightTrigger > max) {
            detected = GLFWBinding.GAMEPAD_AXIS_RT;
        }

        return detected;
    }

    private GLFWBinding detectTriggerButtonBindingFromMotionEvent(MotionEvent event) {
        float leftTrigger = Math.max(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), 0f);
        float rightTrigger = Math.max(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), 0f);

        if (leftTrigger >= AXIS_CAPTURE_THRESHOLD && leftTrigger >= rightTrigger) {
            return GLFWBinding.GAMEPAD_LTRIGGER;
        }
        if (rightTrigger >= AXIS_CAPTURE_THRESHOLD) {
            return GLFWBinding.GAMEPAD_RTRIGGER;
        }
        return null;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (binding != null) {
            binding.controllerConfigRootLl.requestFocus();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
