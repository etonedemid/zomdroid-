package com.zomdroid;

import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import android.view.Surface;

import com.zomdroid.input.InputNativeInterface;
import com.zomdroid.game.GameInstance;

import java.io.File;
import java.util.ArrayList;

public class GameLauncher {
    private static final String LOG_TAG = GameLauncher.class.getName();

    public static void launch(GameInstance gameInstance) throws ErrnoException {
        LauncherPreferences prefs = LauncherPreferences.requireSingleton();

        Os.setenv("LIBGL_MIPMAP", "1", false);

        Os.setenv("BOX64_LOG", "1", false);
        Os.setenv("BOX64_SHOWBT", "1", false);

        Os.setenv("BOX64_LD_LIBRARY_PATH", gameInstance.getLdLibraryPathForEmulation(), false);

        Os.setenv("GALLIUM_DRIVER", "zink", false);

        Os.setenv("ZOMDROID_CACHE_DIR", AppStorage.requireSingleton().getCachePath(), false);
        Os.setenv("ZOMDROID_RENDERER", prefs.getRenderer().name(), false);

        switch (prefs.getRenderer()) {
            case ZINK_ZFA:
            case ZINK_OSMESA:
                String vulkanDriverName = prefs.getVulkanDriver().libName;
                if (vulkanDriverName != null) {
                    Os.setenv("ZOMDROID_VULKAN_DRIVER_NAME", vulkanDriverName, false);
                }
                Os.setenv("ZOMDROID_GLES_MAJOR", "3", false);
                Os.setenv("ZOMDROID_GLES_MINOR", "1", false);
                break;
            default:
                Os.setenv("ZOMDROID_GLES_MAJOR", "2", false);
                Os.setenv("ZOMDROID_GLES_MINOR", "1", false);
                break;
        }

        Os.setenv("ZOMDROID_AUDIO_API", prefs.getAudioAPI().name(), false);

        if (prefs.isDebug()) {
            Os.setenv("BOX64_LOG", "3", true);
            Os.setenv("MESA_DEBUG", "1", false);
        }

        // Apply user-defined environment variables
        String userEnvVars = prefs.getEnvVars();
        if (userEnvVars != null && !userEnvVars.isEmpty()) {
            for (String line : userEnvVars.split("\n")) {
                line = line.trim();
                int eq = line.indexOf('=');
                if (eq > 0) {
                    String key = line.substring(0, eq).trim();
                    String val = line.substring(eq + 1).trim();
                    if (!key.isEmpty()) {
                        Os.setenv(key, val, true);
                        Log.d(LOG_TAG, "User env: " + key + "=" + val);
                    }
                }
            }
        }

        initZomdroidWindow();
        InputNativeInterface.sendJoystickConnected();

        ArrayList<String> jvmArgs = gameInstance.getJvmArgsAsList();

        // Apply user-defined JVM arguments
        String userJvmArgs = prefs.getJvmArgs();
        if (userJvmArgs != null && !userJvmArgs.isEmpty()) {
            for (String arg : userJvmArgs.split("\n")) {
                arg = arg.trim();
                if (!arg.isEmpty()) {
                    jvmArgs.add(arg);
                }
            }
        }

        jvmArgs.add("-Dorg.lwjgl.opengl.libname=" + prefs.getRenderer().libName);
        jvmArgs.add("-Dzomdroid.renderer=" + prefs.getRenderer().name());
        jvmArgs.add("-Dzomboid.steam=0");
        jvmArgs.add("-Dzomboid.znetlog=1");
        jvmArgs.add("-XX:ErrorFile=/dev/stdout");

        ArrayList<String> args = gameInstance.getArgsAsList();

        String homePath = AppStorage.requireSingleton().getHomePath();

        // Select JRE version: GL4ES (Build 41) uses JRE21, newer renderers use JRE25
        String jreFolder = selectJreFolder(homePath, prefs.getRenderer());
        String ldLibraryPath = AppStorage.requireSingleton().getLibraryPath() + ":/system/lib64:"
                + jreFolder + "/lib:" + jreFolder + "/lib/server:" + gameInstance.getJavaLibraryPath();
        GameLauncher.startGame(gameInstance.getGamePath(), ldLibraryPath, jvmArgs.toArray(new String[0]),
                gameInstance.getMainClassName(), args.toArray(new String[0]));
    }

    private static String selectJreFolder(String homePath, LauncherPreferences.Renderer renderer) {
        boolean preferJre21 = isLegacyRendererNeedingJre21(renderer);
        String preferredPath = homePath + "/" + (preferJre21 ? C.deps.JRE_21 : C.deps.JRE_25);
        if (new File(preferredPath).exists()) {
            return preferredPath;
        }
        // Fallback to legacy JRE path if versioned folders don't exist yet
        String legacyPath = homePath + "/" + C.deps.JRE_ROOT;
        if (new File(legacyPath).exists()) {
            return legacyPath;
        }
        return preferredPath;
    }

    private static boolean isLegacyRendererNeedingJre21(LauncherPreferences.Renderer renderer) {
        return renderer == LauncherPreferences.Renderer.GL4ES;
    }

    public static native int initZomdroidWindow();

    public static native void destroyZomdroidWindow();

    public static native int setSurface(Surface surface, int width, int height);

    public static native void destroySurface();

    static native void startGame(String gameDirPath, String libraryDirPath, String[] jvmArgs, String mainClassName, String[] args);
}
