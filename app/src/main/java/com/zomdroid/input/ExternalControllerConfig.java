package com.zomdroid.input;

import static android.content.Context.MODE_PRIVATE;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.google.gson.Gson;
import com.zomdroid.C;

public class ExternalControllerConfig {
    private static final String PREFS_KEY = C.shprefs.keys.EXTERNAL_CONTROLLER_CONFIG;

    public boolean enabled = true;
    public float axisDeadZone = 0.2f;
    // When false, in-game overlay controls (touch UI) are disabled/hidden
    public boolean overlayControlsEnabled = true;

    public GLFWBinding buttonA = GLFWBinding.GAMEPAD_BUTTON_A;
    public GLFWBinding buttonB = GLFWBinding.GAMEPAD_BUTTON_B;
    public GLFWBinding buttonX = GLFWBinding.GAMEPAD_BUTTON_X;
    public GLFWBinding buttonY = GLFWBinding.GAMEPAD_BUTTON_Y;
    public GLFWBinding buttonLb = GLFWBinding.GAMEPAD_BUTTON_LB;
    public GLFWBinding buttonRb = GLFWBinding.GAMEPAD_BUTTON_RB;
    public GLFWBinding buttonBack = GLFWBinding.GAMEPAD_BUTTON_BACK;
    public GLFWBinding buttonStart = GLFWBinding.GAMEPAD_BUTTON_START;
    public GLFWBinding buttonLStick = GLFWBinding.GAMEPAD_BUTTON_LSTICK;
    public GLFWBinding buttonRStick = GLFWBinding.GAMEPAD_BUTTON_RSTICK;

    public GLFWBinding axisLeftX = GLFWBinding.GAMEPAD_AXIS_LX;
    public GLFWBinding axisLeftY = GLFWBinding.GAMEPAD_AXIS_LY;
    public GLFWBinding axisRightX = GLFWBinding.GAMEPAD_AXIS_RX;
    public GLFWBinding axisRightY = GLFWBinding.GAMEPAD_AXIS_RY;
    public GLFWBinding axisLeftTrigger = GLFWBinding.GAMEPAD_AXIS_LT;
    public GLFWBinding axisRightTrigger = GLFWBinding.GAMEPAD_AXIS_RT;

    private transient SharedPreferences sharedPreferences;
    private transient Gson gson;

    public static ExternalControllerConfig load(@NonNull Context context) {
        SharedPreferences sharedPreferences = context.getSharedPreferences(C.shprefs.NAME, MODE_PRIVATE);
        Gson gson = new Gson();
        String json = sharedPreferences.getString(PREFS_KEY, null);
        ExternalControllerConfig config;
        if (json == null) {
            config = new ExternalControllerConfig();
        } else {
            config = gson.fromJson(json, ExternalControllerConfig.class);
            if (config == null) {
                config = new ExternalControllerConfig();
            }
        }
        config.sharedPreferences = sharedPreferences;
        config.gson = gson;
        config.axisDeadZone = Math.clamp(config.axisDeadZone, 0f, 0.95f);
        return config;
    }

    public void save() {
        String json = gson.toJson(this);
        sharedPreferences
                .edit()
                .putString(PREFS_KEY, json)
                .apply();
    }

    public void resetToDefaults() {
        enabled = true;
        axisDeadZone = 0.2f;

        buttonA = GLFWBinding.GAMEPAD_BUTTON_A;
        buttonB = GLFWBinding.GAMEPAD_BUTTON_B;
        buttonX = GLFWBinding.GAMEPAD_BUTTON_X;
        buttonY = GLFWBinding.GAMEPAD_BUTTON_Y;
        buttonLb = GLFWBinding.GAMEPAD_BUTTON_LB;
        buttonRb = GLFWBinding.GAMEPAD_BUTTON_RB;
        buttonBack = GLFWBinding.GAMEPAD_BUTTON_BACK;
        buttonStart = GLFWBinding.GAMEPAD_BUTTON_START;
        buttonLStick = GLFWBinding.GAMEPAD_BUTTON_LSTICK;
        buttonRStick = GLFWBinding.GAMEPAD_BUTTON_RSTICK;

        axisLeftX = GLFWBinding.GAMEPAD_AXIS_LX;
        axisLeftY = GLFWBinding.GAMEPAD_AXIS_LY;
        axisRightX = GLFWBinding.GAMEPAD_AXIS_RX;
        axisRightY = GLFWBinding.GAMEPAD_AXIS_RY;
        axisLeftTrigger = GLFWBinding.GAMEPAD_AXIS_LT;
        axisRightTrigger = GLFWBinding.GAMEPAD_AXIS_RT;

        overlayControlsEnabled = true;

        save();
    }

    public static GLFWBinding[] buttonOptions() {
        return new GLFWBinding[]{
                GLFWBinding.GAMEPAD_BUTTON_A,
                GLFWBinding.GAMEPAD_BUTTON_B,
                GLFWBinding.GAMEPAD_BUTTON_X,
                GLFWBinding.GAMEPAD_BUTTON_Y,
                GLFWBinding.GAMEPAD_BUTTON_LB,
                GLFWBinding.GAMEPAD_BUTTON_RB,
                GLFWBinding.GAMEPAD_BUTTON_BACK,
                GLFWBinding.GAMEPAD_BUTTON_START,
                GLFWBinding.GAMEPAD_BUTTON_GUIDE,
                GLFWBinding.GAMEPAD_BUTTON_LSTICK,
                GLFWBinding.GAMEPAD_BUTTON_RSTICK,
                GLFWBinding.GAMEPAD_LTRIGGER,
                GLFWBinding.GAMEPAD_RTRIGGER,
        };
    }

    public static GLFWBinding[] axisOptions() {
        return new GLFWBinding[]{
                GLFWBinding.GAMEPAD_AXIS_LX,
                GLFWBinding.GAMEPAD_AXIS_LY,
                GLFWBinding.GAMEPAD_AXIS_RX,
                GLFWBinding.GAMEPAD_AXIS_RY,
                GLFWBinding.GAMEPAD_AXIS_LT,
                GLFWBinding.GAMEPAD_AXIS_RT,
        };
    }
}
