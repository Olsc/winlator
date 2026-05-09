package com.winlator.cmod;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Display;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.R;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.math.Mathf;
import com.winlator.cmod.xserver.Keyboard;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.XKeycode;
import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XServer;

/*
    WinlatorXR implementation by lvonasek (https://github.com/lvonasek)
 */

public class XrActivity extends XServerDisplayActivity implements TextWatcher {
    // Order of the enum has to be the as in xr/main.cpp
    public enum ControllerAxis {
        L_PITCH, L_YAW, L_ROLL, L_THUMBSTICK_X, L_THUMBSTICK_Y, L_X, L_Y, L_Z,
        R_PITCH, R_YAW, R_ROLL, R_THUMBSTICK_X, R_THUMBSTICK_Y, R_X, R_Y, R_Z,
        HMD_PITCH, HMD_YAW, HMD_ROLL, HMD_X, HMD_Y, HMD_Z, HMD_IPD
    }

    // Order of the enum has to be the as in xr/main.cpp
    public enum ControllerButton {
        L_GRIP,  L_MENU, L_THUMBSTICK_PRESS, L_THUMBSTICK_LEFT, L_THUMBSTICK_RIGHT, L_THUMBSTICK_UP, L_THUMBSTICK_DOWN, L_TRIGGER, L_X, L_Y,
        R_A, R_B, R_GRIP, R_THUMBSTICK_PRESS, R_THUMBSTICK_LEFT, R_THUMBSTICK_RIGHT, R_THUMBSTICK_UP, R_THUMBSTICK_DOWN, R_TRIGGER,
    }

    private static boolean isDeviceDetectionFinished = false;
    private static boolean isDeviceSupported = false;
    private static boolean isMetaQuest = false;
    private static boolean isEnabled = false;
    private static boolean isImmersive = false;
    private static boolean isSBS = false;
    private static final KeyCharacterMap chars = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
    private static final float[] lastAxes = new float[ControllerAxis.values().length];
    private static final boolean[] lastButtons = new boolean[ControllerButton.values().length];
    private static String lastText = "";
    private static float mouseSpeed = 1;
    private static final float[] smoothedMouse = new float[2];
    private static float screenDistance = 2.0f;
    private static int leftClickDelay = 0;
    private static int rightClickDelay = 0;
    private static XrActivity instance;

    @Override
    public synchronized void onPause() {
        EditText text = findViewById(R.id.XRTextInput);
        text.removeTextChangedListener(this);
        super.onPause();
    }

    @Override
    public synchronized void onResume() {
        super.onResume();
        instance = this;
        mouseSpeed = PreferenceManager.getDefaultSharedPreferences(this).getFloat("cursor_speed", 1.0f);

        // Default to Screen mode for XR devices (World Locked)
        isImmersive = false;
        isSBS = false;

        XServer server = getXServer();
        if (server != null) {
            smoothedMouse[0] = server.screenInfo.width / 2.0f;
            smoothedMouse[1] = server.screenInfo.height / 2.0f;
        }

        AppUtils.hideSystemUI(this);

        EditText text = findViewById(R.id.XRTextInput);
        text.setVisibility(View.VISIBLE);
        text.getEditableText().clear();
        text.addTextChangedListener(this);
    }

    @Override
    public synchronized void onDestroy() {
        super.onDestroy();
        System.exit(0);
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {}

    @Override
    public synchronized void afterTextChanged(Editable e) {
        XServer server = instance.getXServer();
        EditText text = findViewById(R.id.XRTextInput);
        String s = text.getEditableText().toString();
        if (s.length() > lastText.length()) {
            lastText = s;
            KeyEvent[] events = chars.getEvents(new char[]{s.charAt(s.length() - 1)});
            if (events != null) {
                for (KeyEvent keyEvent : events) {
                    server.keyboard.onKeyEvent(keyEvent);
                    sleep(50);
                }
            }
        } else {
            lastText = s;
            server.keyboard.onKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
            sleep(50);
            server.keyboard.onKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL));
        }
        if (s.isEmpty()) {
            resetText();
        }
    }

    private synchronized void resetText() {
        EditText text = findViewById(R.id.XRTextInput);
        text.removeTextChangedListener(this);
        text.getEditableText().clear();
        text.getEditableText().append(" ");
        text.addTextChangedListener(this);
    }

    public static XrActivity getInstance() {
        return instance;
    }

    public static boolean getImmersive() {
        return isImmersive;
    }

    public static boolean getSBS() {
        return isSBS;
    }

    public static boolean isEnabled(Context context) {
        if (context != null) {
            isEnabled = PreferenceManager.getDefaultSharedPreferences(context).getBoolean("use_xr", true);
        }
        return isSupported() && isEnabled;
    }

    public static boolean isSupported() {
        if (!isDeviceDetectionFinished) {
            String manufacturer = Build.MANUFACTURER.toUpperCase();
            if (manufacturer.contains("META") || 
                manufacturer.contains("OCULUS")) {
                isMetaQuest = true;
                isDeviceSupported = true;
            } else if (manufacturer.contains("PICO") || 
                manufacturer.contains("BYTEDANCE") || 
                manufacturer.contains("HTC") || 
                manufacturer.contains("VIVE") || 
                manufacturer.contains("LYNX") || 
                manufacturer.contains("MAGIC LEAP")) {
                isDeviceSupported = true;
            }
            isDeviceDetectionFinished = true;
        }
        return isDeviceSupported;
    }

    public static void openIntent(Activity context, int containerId, String path) {
        // 0. Create the launch intent
        Intent intent = new Intent(context, XrActivity.class);
        intent.putExtra("container_id", containerId);
        if (path != null) {
            intent.putExtra("shortcut_path", path);
        }

        // 1. Locate the main display ID and add that to the intent
        final int mainDisplayId = Display.DEFAULT_DISPLAY;
        ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(mainDisplayId);

        // 2. Set the flags: start in a new task and replace any existing tasks in the app stack
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK |
                Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        // 3. Launch the activity.
        // Don't use the container's ContextWrapper, which is adding arguments
        context.getBaseContext().startActivity(intent, options.toBundle());

        // 4. Finish the previous activity: this avoids an audio bug
        context.finish();
    }

    public static void updateControllers() {
        // Get OpenXR data
        float[] axes = instance.getAxes();
        boolean[] buttons = instance.getButtons();
        int primaryController = instance.container.getPrimaryController();

        // Primary controller mapping
        ControllerAxis mouseAxisX = primaryController == 0 ? ControllerAxis.L_X : ControllerAxis.R_X;
        ControllerAxis mouseAxisY = primaryController == 0 ? ControllerAxis.L_Y : ControllerAxis.R_Y;
        ControllerButton primaryGrip = primaryController == 0 ? ControllerButton.L_GRIP : ControllerButton.R_GRIP;
        ControllerButton primaryTrigger = primaryController == 0 ? ControllerButton.L_TRIGGER : ControllerButton.R_TRIGGER;
        ControllerButton primaryUp = primaryController == 0 ? ControllerButton.L_THUMBSTICK_UP : ControllerButton.R_THUMBSTICK_UP;
        ControllerButton primaryDown = primaryController == 0 ? ControllerButton.L_THUMBSTICK_DOWN : ControllerButton.R_THUMBSTICK_DOWN;
        ControllerButton primaryLeft = primaryController == 0 ? ControllerButton.L_THUMBSTICK_LEFT : ControllerButton.R_THUMBSTICK_LEFT;
        ControllerButton primaryRight = primaryController == 0 ? ControllerButton.L_THUMBSTICK_RIGHT : ControllerButton.R_THUMBSTICK_RIGHT;
        ControllerButton primaryPress = primaryController == 0 ? ControllerButton.L_THUMBSTICK_PRESS : ControllerButton.R_THUMBSTICK_PRESS;
        ControllerButton secondaryGrip = primaryController == 1 ? ControllerButton.L_GRIP : ControllerButton.R_GRIP;
        ControllerButton secondaryTrigger = primaryController == 1 ? ControllerButton.L_TRIGGER : ControllerButton.R_TRIGGER;
        ControllerButton secondaryUp = primaryController == 1 ? ControllerButton.L_THUMBSTICK_UP : ControllerButton.R_THUMBSTICK_UP;
        ControllerButton secondaryDown = primaryController == 1 ? ControllerButton.L_THUMBSTICK_DOWN : ControllerButton.R_THUMBSTICK_DOWN;
        ControllerButton secondaryLeft = primaryController == 1 ? ControllerButton.L_THUMBSTICK_LEFT : ControllerButton.R_THUMBSTICK_LEFT;
        ControllerButton secondaryRight = primaryController == 1 ? ControllerButton.L_THUMBSTICK_RIGHT : ControllerButton.R_THUMBSTICK_RIGHT;
        ControllerButton secondaryPress = primaryController == 1 ? ControllerButton.L_THUMBSTICK_PRESS : ControllerButton.R_THUMBSTICK_PRESS;

        try (XLock lock = instance.getXServer().lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
            // Mouse control with thumbstick
            int mouseXAxis = primaryController == 0 ? ControllerAxis.L_THUMBSTICK_X.ordinal() : ControllerAxis.R_THUMBSTICK_X.ordinal();
            int mouseYAxis = primaryController == 0 ? ControllerAxis.L_THUMBSTICK_Y.ordinal() : ControllerAxis.R_THUMBSTICK_Y.ordinal();
            float sensitivityMultiplier = (primaryController == 1) ? 12.0f : 20.0f;
            float dx = axes[mouseXAxis] * mouseSpeed * sensitivityMultiplier;
            float dy = axes[mouseYAxis] * mouseSpeed * sensitivityMultiplier;

            // Pause thumbstick movement when clicking to prevent accidental drift
            boolean isClicking = buttons[ControllerButton.R_TRIGGER.ordinal()] || buttons[ControllerButton.R_GRIP.ordinal()] ||
                                 buttons[ControllerButton.L_TRIGGER.ordinal()] || buttons[ControllerButton.L_GRIP.ordinal()];
            if (isClicking) {
                dx = 0;
                dy = 0;
            }

            // Invert movement for Meta Quest devices as requested
            if (isMetaQuest) {
                dx = -dx;
                dy = -dy;
            }

            // Right thumbstick Y for screen distance (Forward -> Back, Backward -> Forward)
            float ry = axes[ControllerAxis.R_THUMBSTICK_Y.ordinal()];
            if (Math.abs(ry) > 0.1f) {
                screenDistance += ry * 0.05f;
                screenDistance = Mathf.clamp(screenDistance, 0.5f, 10.0f);
                instance.setCanvasDistance(screenDistance);
            }

            // Mouse control with head
            Pointer mouse = instance.getXServer().pointer;
            if (isImmersive) {
                smoothedMouse[0] = mouse.getClampedX() + 0.5f;
                smoothedMouse[1] = mouse.getClampedY() + 0.5f;
            }

            // Mouse smoothing and delta update
            smoothedMouse[0] += dx;
            smoothedMouse[1] -= dy;

            // Clamp to screen bounds
            smoothedMouse[0] = Mathf.clamp(smoothedMouse[0], 0, instance.getXServer().screenInfo.width);
            smoothedMouse[1] = Mathf.clamp(smoothedMouse[1], 0, instance.getXServer().screenInfo.height);

            // Mouse "snap turn"
            int snapturn = isImmersive ? 125 : 25;
            if (getButtonClicked(buttons, primaryLeft)) {
                smoothedMouse[0] = mouse.getClampedX() - snapturn;
            }
            if (getButtonClicked(buttons, primaryRight)) {
                smoothedMouse[0] = mouse.getClampedX() + snapturn;
            }

            // System functions (Immersive / SBS toggle)
            if (getButtonClicked(buttons, secondaryPress) && buttons[secondaryGrip.ordinal()]) {
                if (buttons[primaryGrip.ordinal()]) {
                    isSBS = !isSBS;
                }
                else {
                    isImmersive = !isImmersive;
                }
            }

            // Ray interaction (Only for Right Hand)
            updateRayInteraction(buttons, primaryTrigger, primaryGrip, primaryUp, primaryDown);

            // Show system keyboard
            if (getButtonClicked(buttons, primaryPress)) {
                instance.runOnUiThread(() -> {
                    isSBS = false;
                    isImmersive = false;
                    instance.resetText();
                    AppUtils.showKeyboard(instance);
                    instance.findViewById(R.id.XRTextInput).requestFocus();
                });
            }

            // Store the OpenXR data
            System.arraycopy(axes, 0, lastAxes, 0, axes.length);
            System.arraycopy(buttons, 0, lastButtons, 0, buttons.length);

            // Update keyboard
            mapKey(buttons, ControllerButton.L_MENU, XKeycode.KEY_ESC.id);
            mapKey(buttons, ControllerButton.R_A, instance.container.getControllerMapping(Container.XrControllerMapping.BUTTON_A));
            mapKey(buttons, ControllerButton.R_B, instance.container.getControllerMapping(Container.XrControllerMapping.BUTTON_B));
            mapKey(buttons, ControllerButton.L_X, instance.container.getControllerMapping(Container.XrControllerMapping.BUTTON_X));
            mapKey(buttons, ControllerButton.L_Y, instance.container.getControllerMapping(Container.XrControllerMapping.BUTTON_Y));
            mapKey(buttons, secondaryGrip, instance.container.getControllerMapping(Container.XrControllerMapping.BUTTON_GRIP));
            mapKey(buttons, secondaryTrigger, instance.container.getControllerMapping(Container.XrControllerMapping.BUTTON_TRIGGER));
            mapKey(buttons, secondaryUp, instance.container.getControllerMapping(Container.XrControllerMapping.THUMBSTICK_UP));
            mapKey(buttons, secondaryDown, instance.container.getControllerMapping(Container.XrControllerMapping.THUMBSTICK_DOWN));
            mapKey(buttons, secondaryLeft, instance.container.getControllerMapping(Container.XrControllerMapping.THUMBSTICK_LEFT));
            mapKey(buttons, secondaryRight, instance.container.getControllerMapping(Container.XrControllerMapping.THUMBSTICK_RIGHT));

            // Map secondary thumbstick press if grip is NOT held (otherwise it's used for system toggle)
            if (!buttons[secondaryGrip.ordinal()]) {
                mapKey(buttons, secondaryPress, instance.container.getControllerMapping(Container.XrControllerMapping.THUMBSTICK_PRESS));
            }
            else {
                // Ensure key is released if we started holding grip
                byte thumbPressKey = instance.container.getControllerMapping(Container.XrControllerMapping.THUMBSTICK_PRESS);
                if (thumbPressKey != 0) instance.getXServer().keyboard.setKeyRelease(thumbPressKey);
            }
        }
    }

    private static void updateRayInteraction(boolean[] buttons, ControllerButton primaryTrigger, ControllerButton primaryGrip, ControllerButton primaryUp, ControllerButton primaryDown) {
        float[] poses = instance.getControllerPoses();
        float distance = screenDistance;
        float quadWidth = 4.0f;
        float quadHeight = quadWidth * (9.0f / 16.0f);

        boolean hit = false;
        // Only use Right Hand (index 1)
        for (int i = 1; i < 2; i++) {
            float px = poses[i*7], py = poses[i*7+1], pz = poses[i*7+2];
            float qx = poses[i*7+3], qy = poses[i*7+4], qz = poses[i*7+5], qw = poses[i*7+6];

            // Ray direction (forward is -Z in OpenXR)
            float vx = 2 * (qx * qz - qw * qy);
            float vy = 2 * (qy * qz + qw * qx);
            float vz = -(1 - 2 * (qx * qx + qy * qy));

            if (vz < -0.01f) {
                float t = (-distance - pz) / vz;
                if (t > 0) {
                    float hx = px + t * vx;
                    float hy = py + t * vy;

                    float u = (hx / quadWidth) + 0.5f;
                    float v = 0.5f - (hy / quadHeight);

                    if (u >= 0 && u <= 1 && v >= 0 && v <= 1) {
                        Pointer mouse = instance.getXServer().pointer;
                        int tx = (int)(u * instance.getXServer().screenInfo.width);
                        int ty = (int)(v * instance.getXServer().screenInfo.height);
                        
                        boolean trigger = buttons[ControllerButton.R_TRIGGER.ordinal()];
                        boolean grip = buttons[ControllerButton.R_GRIP.ordinal()];
                        
                        // Apply low-pass filtering to reduce jitter and improve double-click stability
                        // When clicking, use absolute smoothing (1.0f) to freeze the cursor completely
                        float smoothing = (trigger || grip) ? 1.0f : 0.8f;
                        smoothedMouse[0] = smoothedMouse[0] * smoothing + tx * (1.0f - smoothing);
                        smoothedMouse[1] = smoothedMouse[1] * smoothing + ty * (1.0f - smoothing);
                        
                        // Update mouse position (using smoothed values)
                        mouse.setPosition((int)smoothedMouse[0], (int)smoothedMouse[1]);
                        
                        // Stabilization delay: only trigger the click after the cursor has been frozen for a few frames
                        if (trigger) leftClickDelay++; else leftClickDelay = 0;
                        if (grip) rightClickDelay++; else rightClickDelay = 0;
                        
                        // Update mouse buttons (Left Click for Trigger, Right Click for Grip)
                        // Using a 3-frame delay for the 'down' event to ensure stability
                        mouse.setButton(Pointer.Button.BUTTON_LEFT, leftClickDelay >= 3);
                        mouse.setButton(Pointer.Button.BUTTON_RIGHT, rightClickDelay >= 3);
                        
                        hit = true;
                        break;
                    }
                }
            }
        }

        // Fallback to thumbstick mouse if no ray hit
        if (!hit) {
            Pointer mouse = instance.getXServer().pointer;
            mouse.setPosition((int) smoothedMouse[0], (int) smoothedMouse[1]);
            mouse.setButton(Pointer.Button.BUTTON_LEFT, buttons[primaryTrigger.ordinal()]);
            mouse.setButton(Pointer.Button.BUTTON_RIGHT, buttons[primaryGrip.ordinal()]);
            mouse.setButton(Pointer.Button.BUTTON_SCROLL_UP, buttons[primaryUp.ordinal()]);
            mouse.setButton(Pointer.Button.BUTTON_SCROLL_DOWN, buttons[primaryDown.ordinal()]);
        }
    }

    private static float getAngleDiff(float oldAngle, float newAngle) {
        float diff = oldAngle - newAngle;
        while (diff > 180) {
            diff -= 360;
        }
        while (diff < -180) {
            diff += 360;
        }
        return diff;
    }

    private static boolean getButtonClicked(boolean[] buttons, ControllerButton button) {
        return buttons[button.ordinal()] && !lastButtons[button.ordinal()];
    }

    private static void mapKey(boolean[] buttons, ControllerButton xrButton, byte xKeycode) {
        if (xKeycode == 0) return;
        Keyboard keyboard = instance.getXServer().keyboard;
        if (buttons[xrButton.ordinal()]) {
            keyboard.setKeyPress(xKeycode, 0);
        } else {
            keyboard.setKeyRelease(xKeycode);
        }
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    // Rendering
    public native void init();
    public native void setCanvasDistance(float distance);
    public native void bindFramebuffer();
    public native void bindScreenFramebuffer();
    public native int getWidth();
    public native int getHeight();
    public native boolean beginFrame(boolean immersive, boolean sbs, float aspect);
    public native void beginScreen();
    public native void endScreen();
    public native void beginEye(int eye);
    public native void endEye();
    public native void endFrame();

    // Input
    public native float[] getAxes();
    public native boolean[] getButtons();
    public native float[] getControllerPoses();
    public native float[] getFov(int eye);
}