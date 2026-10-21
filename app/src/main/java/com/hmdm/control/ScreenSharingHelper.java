package com.hmdm.control;

import android.content.Context;
import android.content.Intent;
import android.graphics.Point;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

/**
 * Helper API for interaction between MainActivity and ScreenSharingService
 */
public class ScreenSharingHelper {
    // Scale down screen size to reduce the video traffic
    public static float adjustScreenMetrics(DisplayMetrics metrics) {
        int srcWidth = metrics.widthPixels;
        // Adjust translated screencast size for phones with high screen resolutions
        if (metrics.widthPixels > Const.MAX_SHARED_SCREEN_WIDTH || metrics.heightPixels > Const.MAX_SHARED_SCREEN_HEIGHT) {
            float widthScale = (float)metrics.widthPixels / Const.MAX_SHARED_SCREEN_WIDTH;
            float heightScale = (float)metrics.heightPixels / Const.MAX_SHARED_SCREEN_HEIGHT;
            float maxScale = widthScale > heightScale ? widthScale : heightScale;
            metrics.widthPixels /= maxScale;
            metrics.heightPixels /= maxScale;
        }

        float videoScale = (float)metrics.widthPixels / srcWidth;
        Log.i(Const.LOG_TAG, "screenWidth=" + metrics.widthPixels + ", screenHeight=" + metrics.heightPixels + ", scale=" + videoScale);
        // Workaround against the codec bug: https://stackoverflow.com/questions/36915383/what-does-error-code-1010-in-android-mediacodec-mean
        // Making height and width divisible by 2
        metrics.heightPixels = metrics.heightPixels & 0xFFFE;
        metrics.widthPixels = metrics.widthPixels & 0xFFFE;
        return videoScale;
    }

    // getDefaultDisplay() excludes status bar and nav bar, so we need to get the full screen size
    public static void getRealScreenSize(Context context, DisplayMetrics metrics) {
        WindowManager wm = ((WindowManager)
                context.getSystemService(Context.WINDOW_SERVICE));
        Display display = wm.getDefaultDisplay();

        // This gets correct screen density, but wrong width and height
        display.getMetrics(metrics);

        Point screenSize = new Point();
        display.getRealSize(screenSize);
        metrics.widthPixels = screenSize.x;
        metrics.heightPixels = screenSize.y;
    }

    public static void setScreenMetrics(Context context, int screenWidth, int screenHeight, int screenDensity) {
        Intent intent = new Intent(context, ScreenSharingService.class);
        intent.setAction(ScreenSharingService.ACTION_SET_METRICS);
        intent.putExtra(ScreenSharingService.ATTR_SCREEN_WIDTH, screenWidth);
        intent.putExtra(ScreenSharingService.ATTR_SCREEN_HEIGHT, screenHeight);
        intent.putExtra(ScreenSharingService.ATTR_SCREEN_DENSITY, screenDensity);
        executeCommand(context, intent);
    }

    public static void configure(Context context, boolean audio, int videoFrameRate, int videoBitRate, String host, int audioPort, int videoPort) {
        Intent intent = new Intent(context, ScreenSharingService.class);
        intent.setAction(ScreenSharingService.ACTION_CONFIGURE);
        intent.putExtra(ScreenSharingService.ATTR_AUDIO, audio);
        intent.putExtra(ScreenSharingService.ATTR_FRAME_RATE, videoFrameRate);
        intent.putExtra(ScreenSharingService.ATTR_BITRATE, videoBitRate);
        intent.putExtra(ScreenSharingService.ATTR_HOST, host);
        intent.putExtra(ScreenSharingService.ATTR_AUDIO_PORT, audioPort);
        intent.putExtra(ScreenSharingService.ATTR_VIDEO_PORT, videoPort);
        executeCommand(context, intent);
    }

    public static void requestSharing(Context context) {
        Intent intent = new Intent(context, ScreenSharingService.class);
        intent.setAction(ScreenSharingService.ACTION_REQUEST_SHARING);
        executeCommand(context, intent);
    }

    public static void startSharing(Context context, int resultCode, Intent data) {
        Intent intent = new Intent(context, ScreenSharingService.class);
        intent.setAction(ScreenSharingService.ACTION_START_SHARING);
        intent.putExtra(ScreenSharingService.ATTR_RESULT_CODE, resultCode);
        intent.putExtra(ScreenSharingService.ATTR_DATA, data);
        executeCommand(context, intent);
    }

    public static void stopSharing(Context context, boolean finalStop) {
        Intent intent = new Intent(context, ScreenSharingService.class);
        intent.setAction(ScreenSharingService.ACTION_STOP_SHARING);
        intent.putExtra(ScreenSharingService.ATTR_DESTROY_MEDIA_PROJECTION, finalStop);
        executeCommand(context, intent);
    }

    private static void executeCommand(Context context, Intent intent) {
        // Only ACTION_START_SHARING may go through startForegroundService(): the
        // service becomes a foreground service of type mediaProjection right after
        // it obtains the projection token, which is what API 34+ requires. Every
        // other command runs as a regular started service, so the 5s
        // "did not call startForeground()" watchdog never applies to them.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && ScreenSharingService.ACTION_START_SHARING.equals(intent.getAction())) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }
}
