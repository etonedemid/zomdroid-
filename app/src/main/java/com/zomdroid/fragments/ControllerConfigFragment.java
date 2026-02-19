package com.zomdroid.fragments;

import android.os.Bundle;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.zomdroid.R;
import com.zomdroid.databinding.ElementControllerMappingRowBinding;
import com.zomdroid.databinding.FragmentControllerConfigBinding;
import com.zomdroid.input.ExternalControllerConfig;
import com.zomdroid.input.GLFWBinding;

public class ControllerConfigFragment extends Fragment {
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
            if (event.getAction() == KeyEvent.ACTION_DOWN || event.getAction() == KeyEvent.ACTION_UP) {
                binding.controllerConfigPreviewTv.setText(getString(
                        R.string.controller_config_preview_button,
                        KeyEvent.keyCodeToString(keyCode),
                        event.getAction() == KeyEvent.ACTION_DOWN ? getString(R.string.controller_config_state_down) : getString(R.string.controller_config_state_up)
                ));
            }
            return false;
        });

        binding.controllerConfigRootLl.setOnGenericMotionListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_MOVE && isJoystickEvent(event)) {
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
            }
            return false;
        });
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

    private void addMappingRow(LinearLayout parent, int labelRes, GLFWBinding[] options, BindingAccessor accessor) {
        ElementControllerMappingRowBinding rowBinding = ElementControllerMappingRowBinding.inflate(getLayoutInflater(), parent, true);
        TextView labelTv = rowBinding.controllerMappingLabelTv;
        Spinner valueS = rowBinding.controllerMappingValueS;

        labelTv.setText(labelRes);

        ArrayAdapter<GLFWBinding> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, options);
        valueS.setAdapter(adapter);
        valueS.setSelection(adapter.getPosition(accessor.get()));
        valueS.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parentView, View selectedView, int position, long id) {
                GLFWBinding selected = (GLFWBinding) parentView.getSelectedItem();
                accessor.set(selected);
                config.save();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parentView) {
            }
        });
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
