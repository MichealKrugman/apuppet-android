package com.hmdm.control;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import net.majorkernelpanic.streaming.rtp.AbstractPacketizer;
import net.majorkernelpanic.streaming.rtp.H264Packetizer;
import net.majorkernelpanic.streaming.rtp.MediaCodecInputStream;

import java.io.IOException;
import java.net.InetAddress;

public class ScreenSharingService extends Service {
    public static String CHANNEL_ID = "com.hmdm.control";
    private static final int NOTIFICATION_ID = 111;

    private static final String MIME_TYPE_VIDEO = "video/avc";

    private int mScreenDensity;
    private int mScreenWidth;
    private int mScreenHeight;

    private boolean mRecordAudio;
    private String mRtpHost;
    private int mRtpAudioPort;
    private int mRtpVideoPort;
    private int mVideoFrameRate;
    private int mVideoBitrate;

    private MediaProjectionManager mProjectionManager;
    private MediaProjection mMediaProjection;
    private MediaProjection.Callback mMediaProjectionCallback;
    private VirtualDisplay mVirtualDisplay;

    private MediaCodec mMediaCodec;
    private Surface mInputSurface;

    // True between ACTION_REQUEST_SHARING and ACTION_STOP_SHARING: the current
    // session still wants screen capture. A consent result that arrives after a
    // stop (user left the system dialog pending, admin disconnected meanwhile)
    // must be refused, not started.
    private boolean mConsentRequested;

    private AbstractPacketizer mPacketizer;

    public static final String ACTION_SET_METRICS = "metrics";
    public static final String ACTION_CONFIGURE = "configure";
    public static final String ACTION_REQUEST_SHARING = "request";
    public static final String ACTION_START_SHARING = "start";
    public static final String ACTION_STOP_SHARING = "stop";
    public static final String ATTR_SCREEN_WIDTH = "screenWidth";
    public static final String ATTR_SCREEN_HEIGHT = "screenHeight";
    public static final String ATTR_SCREEN_DENSITY = "screenDensity";
    public static final String ATTR_AUDIO = "audio";
    public static final String ATTR_FRAME_RATE = "frameRate";
    public static final String ATTR_BITRATE = "bitrate";
    public static final String ATTR_HOST = "host";
    public static final String ATTR_AUDIO_PORT = "audioPort";
    public static final String ATTR_VIDEO_PORT = "videoPort";
    public static final String ATTR_RESULT_CODE = "resultCode";
    public static final String ATTR_DATA = "data";
    public static final String ATTR_DESTROY_MEDIA_PROJECTION = "destroyMediaProjection";

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        Log.d(Const.LOG_TAG, "ScreenSharingService created");
        mPacketizer = new H264Packetizer();
        mProjectionManager = (MediaProjectionManager)getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        // We do NOT become foreground here: only the ACTION_START_SHARING command
        // needs the mediaProjection foreground-service type, and it becomes
        // foreground at the top of startSharing() -- BEFORE getMediaProjection(),
        // which on API 34 throws SecurityException unless the mediaProjection FGS
        // is already running. (Verified on the target device: the contrary order
        // crashes with "Media projections require a foreground service of type
        // ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION".)
    }

    private void startAsForeground() {
        NotificationCompat.Builder builder;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Notification Channel", NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            notificationManager.createNotificationChannel(channel);
            builder = new NotificationCompat.Builder(this, CHANNEL_ID);
        } else {
            builder = new NotificationCompat.Builder(this);
        }
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        // FLAG_IMMUTABLE is mandatory when targeting API 31+ (the platform throws
        // IllegalArgumentException without it). This PendingIntent only opens our
        // own MainActivity and is never mutated, so immutable is also correct.
        PendingIntent pendingIntent = PendingIntent.getActivity(this, Const.REQUEST_FROM_NOTIFICATION, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = builder
                .setContentTitle(getString(R.string.app_name))
                .setTicker(getString(R.string.app_name))
                .setContentText(getString(R.string.notification_text))
                .setContentIntent(pendingIntent)
                .setSmallIcon(R.drawable.ic_notification).build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Pass the type explicitly: API 34 enforces, at getMediaProjection()
            // time, that this service is already a foreground service of type
            // mediaProjection. The manifest declares the type; passing it
            // explicitly here is the form guaranteed to register it.
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            return Service.START_STICKY;
        }
        String action = intent.getAction();
        Log.d(Const.LOG_TAG, "ScreenSharingService got command: " + action);
        if (action.equals(ACTION_SET_METRICS)) {
            int newWidth = intent.getIntExtra(ATTR_SCREEN_WIDTH, 0);
            int newHeight = intent.getIntExtra(ATTR_SCREEN_HEIGHT, 0);
            int newDensity = intent.getIntExtra(ATTR_SCREEN_DENSITY, 0);
            boolean resizing = (mMediaProjection != null && mVirtualDisplay != null)
                    && (newWidth != mScreenWidth || newHeight != mScreenHeight || newDensity != mScreenDensity);
            mScreenWidth = newWidth;
            mScreenHeight = newHeight;
            mScreenDensity = newDensity;
            Log.d(Const.LOG_TAG, "ScreenSharingService: width=" + mScreenWidth + ", height=" + mScreenHeight + ", density=" + mScreenDensity);
            if (resizing) {
                // Rotation while capturing: the virtual display and encoder
                // were built for the old orientation, so the admin kept
                // receiving 360x800 frames of a now-landscape screen (wrong
                // aspect ratio). Rebuild the capture pipeline at the new size;
                // the projection token is unchanged, so consent stays intact.
                resizeCapture();
            }
        } else if (action.equals(ACTION_CONFIGURE)) {
            configure(intent.getBooleanExtra(ATTR_AUDIO, false),
                    intent.getIntExtra(ATTR_FRAME_RATE, 0),
                    intent.getIntExtra(ATTR_BITRATE, 0),
                    intent.getStringExtra(ATTR_HOST),
                    intent.getIntExtra(ATTR_AUDIO_PORT, 0),
                    intent.getIntExtra(ATTR_VIDEO_PORT, 0));

        } else if (action.equals(ACTION_REQUEST_SHARING)) {
            requestSharing();

        } else if (action.equals(ACTION_START_SHARING)) {
            int resultCode = intent.getIntExtra(ATTR_RESULT_CODE, 0);
            Intent data = intent.getParcelableExtra(ATTR_DATA);
            startSharing(resultCode, data);

        } else if (action.equals(ACTION_STOP_SHARING)) {
            boolean destroyMediaProjection = intent.getBooleanExtra(ATTR_DESTROY_MEDIA_PROJECTION, false);
            stopSharing(destroyMediaProjection);
        }

        return Service.START_STICKY;
    }

    private void configure(boolean audio, int videoFrameRate, int videoBitRate, String host, int audioPort, int videoPort) {
        mVideoFrameRate = videoFrameRate;
        mVideoBitrate = videoBitRate;
        Log.d(Const.LOG_TAG, "ScreenSharingService: frameRate=" + mVideoFrameRate + ", bitrate=" + mVideoBitrate);

        // This is executed in the background because the operation requires host resolution
        new AsyncTask<Void,Void,Void>() {

            @Override
            protected Void doInBackground(Void... voids) {
                try {
                    // Here I set RTCP port to videoPort+1 (conventional), but RTCP is not used, and 0 or -1 cause errors in libstreaming
                    mPacketizer.setDestination(InetAddress.getByName(host), videoPort, videoPort + 1);
                    mPacketizer.setTimeToLive(64);

                } catch (Exception e) {
                    // We should not be here because configure() is called after successful connection to the host
                    e.printStackTrace();
                }
                return null;
            }

        }.execute();

    }

    private void requestSharing() {
        // Idempotent on rejoin: with the textroom room surviving flaps, the
        // "admin already in the room" resumption path fires this again after
        // every reconnect while capture is still running. Tearing down the
        // live projection to re-ask consent (or stacking a second dialog)
        // would break a working session -- ignore duplicates instead.
        if (mMediaProjection != null && mVirtualDisplay != null) {
            Log.d(Const.LOG_TAG, "ScreenSharingService: already sharing, ignoring duplicate request");
            return;
        }
        if (mConsentRequested) {
            Log.d(Const.LOG_TAG, "ScreenSharingService: consent already pending, ignoring duplicate request");
            return;
        }
        // Android 14+ treats the screen-capture grant as a single-use token: it
        // must not be silently reused for a later sharing session. Drop any
        // projection retained from a previous session so tryShareScreen() always
        // re-asks the user for consent on this one. destroyMediaProjection()
        // unregisters our callback before stopping, so the MediaProjection
        // onStop() path does not fire here.
        destroyMediaProjection();
        mConsentRequested = true;
        if (!initRecorder()) {
            // Some initialization error, report to activity
            Intent intent = new Intent(Const.ACTION_SCREEN_SHARING_FAILED);
            intent.putExtra(Const.EXTRA_MESSAGE, getString(R.string.sharing_error));
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
            return;
        }
        tryShareScreen();
    }


    private void tryShareScreen() {
        if (mMediaProjection == null) {
            LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(Const.ACTION_SCREEN_SHARING_PERMISSION_NEEDED));
            return;
        }
        mVirtualDisplay = createVirtualDisplay();
        mMediaCodec.start();
        startSending();
    }

    private void startSharing(int resultCode, Intent data) {
        // Android 14 (API 34) requires the mediaProjection foreground service to
        // be running BEFORE getMediaProjection() is called; otherwise the system
        // throws SecurityException. Become foreground first, then take the token.
        startAsForeground();
        try {
            mMediaProjection = mProjectionManager.getMediaProjection(resultCode, data);
        } catch (SecurityException e) {
            // Defensive: never crash-loop over a consent/FGS edge case.
            Log.e(Const.LOG_TAG, "ScreenSharingService: getMediaProjection failed", e);
            LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(Const.ACTION_SCREEN_SHARING_FAILED));
            stopForeground(true);
            stopSelf();
            return;
        }
        if (mMediaProjection == null) {
            // Consent was revoked/expired between the dialog and this command.
            // Must not continue: we were started with startForegroundService() and
            // would otherwise trip the "did not call startForeground()" watchdog.
            Log.e(Const.LOG_TAG, "ScreenSharingService: getMediaProjection returned null");
            LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(Const.ACTION_SCREEN_SHARING_FAILED));
            stopForeground(true);
            stopSelf();
            return;
        }
        // Refuse a late consent grant: if a stop ran while the system consent
        // dialog was still pending (admin disconnected mid-prompt), the session
        // that asked for consent is gone. Starting capture now would run the
        // encoder and show the "remotely controlled" notification with nobody
        // watching. Drop the freshly granted token and bow out cleanly.
        if (!mConsentRequested) {
            Log.w(Const.LOG_TAG, "ScreenSharingService: consent arrived after stop, ignoring");
            destroyMediaProjection();
            LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(Const.ACTION_SCREEN_SHARING_FAILED));
            stopForeground(true);
            stopSelf();
            return;
        }
        if (mScreenWidth <= 0 || mScreenHeight <= 0 || mScreenDensity <= 0) {
            // A start intent redelivered to a restarted process can reach this
            // point before ACTION_SET_METRICS; createVirtualDisplay() would
            // throw "Virtual display width must be positive" and kill the app.
            Log.e(Const.LOG_TAG, "ScreenSharingService: start without screen metrics, refusing");
            destroyMediaProjection();
            LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(Const.ACTION_SCREEN_SHARING_FAILED));
            stopForeground(true);
            stopSelf();
            return;
        }
        // The encoder built in requestSharing() may since have been stopped or
        // released by an intervening stop command (admin left while the consent
        // dialog was up), and MediaCodec.start() on it throws, which used to
        // crash the whole process. Always start from a fresh configured codec.
        if (mMediaCodec != null) {
            try {
                mMediaCodec.release();
            } catch (Exception ignored) {
            }
            mMediaCodec = null;
        }
        if (!initRecorder()) {
            LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(Const.ACTION_SCREEN_SHARING_FAILED));
            stopForeground(true);
            stopSelf();
            return;
        }
        mMediaProjectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                super.onStop();
                stopSharing(false);
                LocalBroadcastManager.getInstance(ScreenSharingService.this).sendBroadcast(new Intent(Const.ACTION_SCREEN_SHARING_STOP));
            }
        };
        try {
            mMediaProjection.registerCallback(mMediaProjectionCallback, null);
            mVirtualDisplay = createVirtualDisplay();
            mMediaCodec.start();
            startSending();
            // Success anchor for tests/review: pairing with the refusal logs
            // above tells you definitively whether a start attempt ran capture.
            Log.i(Const.LOG_TAG, "ScreenSharingService: sharing started (virtual display + encoder up)");
        } catch (Exception e) {
            // Never let a start failure kill the process: that would take the
            // connection supervisor down with it and break the guarantee that
            // the agent stays connected. Report the failure and stop cleanly.
            Log.e(Const.LOG_TAG, "ScreenSharingService: start failed", e);
            try {
                stopSharing(false);
            } catch (Exception ignored) {
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(Const.ACTION_SCREEN_SHARING_FAILED));
            stopForeground(true);
            stopSelf();
        }

    }

    public void stopSharing(boolean destroyMediaProjection) {
        mConsentRequested = false;
        try {
            mPacketizer.stop();
            if (mMediaCodec != null) {
                mMediaCodec.stop();
            }
            Log.v(Const.LOG_TAG, "Stopping Recording");
            stopScreenSharing(destroyMediaProjection);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Rebuild the capture pipeline at the current (rotated) screen metrics.
    //
    // Android 14 constraint (verified against AOSP
    // MediaProjectionManagerService.MediaProjection.isValid(), 2026-10-22):
    // ONE consent instance permits exactly ONE createVirtualDisplay ever --
    // "Set if MediaProjection#createVirtualDisplay has been invoked
    // previously (it should only be called once)". A second createVirtualDisplay
    // throws SecurityException("Don't take multiple captures by invoking
    // MediaProjection#createVirtualDisplay multiple times on the same
    // instance"). So the display instance is kept and reconfigured instead:
    // VirtualDisplay.resize() + VirtualDisplay.setSurface() are same-instance
    // calls that do not re-enter createVirtualDisplay.
    //
    // mMediaProjection is kept throughout: this never re-prompts the user.
    // The packetizer re-sends SPS/PPS with the first IDR of the new stream
    // (I-frame interval is 1s), so the admin's decoder adopts the new
    // resolution on its own.
    private void resizeCapture() {
        Log.i(Const.LOG_TAG, "ScreenSharingService: resizing capture to "
                + mScreenWidth + "x" + mScreenHeight + " density=" + mScreenDensity);
        MediaCodec oldCodec = mMediaCodec;
        // Old surface stays bound until the new codec is ready and the swap
        // succeeds, so a failure below leaves no dangling state.
        try {
            mPacketizer.stop();
        } catch (Exception ignored) {
        }
        if (!initRecorder()) {
            Log.e(Const.LOG_TAG, "ScreenSharingService: resize re-init failed; stopping capture");
            try {
                if (oldCodec != null) {
                    oldCodec.stop();
                    oldCodec.release();
                }
            } catch (Exception ignored) {
            }
            mMediaCodec = null;
            try {
                stopSharing(false);
            } catch (Exception ignored) {
            }
            return;
        }
        try {
            mVirtualDisplay.resize(mScreenWidth, mScreenHeight, mScreenDensity);
            mVirtualDisplay.setSurface(mInputSurface);
        } catch (Exception e) {
            Log.e(Const.LOG_TAG, "ScreenSharingService: resize rejected by platform; stopping capture", e);
            try {
                mMediaCodec.stop();
                mMediaCodec.release();
            } catch (Exception ignored) {
            }
            mMediaCodec = null;
            try {
                if (oldCodec != null) {
                    oldCodec.stop();
                    oldCodec.release();
                }
            } catch (Exception ignored) {
            }
            try {
                if (mVirtualDisplay != null) {
                    mVirtualDisplay.release();
                }
            } catch (Exception ignored) {
            }
            mVirtualDisplay = null;
            stopForeground(true);
            return;
        }
        // Swap complete: the old surface is detached from the display, so the
        // old codec can go.
        try {
            if (oldCodec != null) {
                oldCodec.stop();
                oldCodec.release();
            }
        } catch (Exception ignored) {
        }
        try {
            mMediaCodec.start();
            startSending();
            Log.i(Const.LOG_TAG, "ScreenSharingService: resize complete ("
                    + mScreenWidth + "x" + mScreenHeight + ")");
        } catch (Exception e) {
            Log.e(Const.LOG_TAG, "ScreenSharingService: resize start failed", e);
            try {
                stopSharing(false);
            } catch (Exception ignored) {
            }
        }
    }

    private void stopScreenSharing(boolean destroyMediaProjection) {
        if (mVirtualDisplay == null) {
            return;
        }
        mPacketizer.stop();
        mVirtualDisplay.release();
        mVirtualDisplay = null;
        mMediaCodec.release();
        // Null both members: a stale non-null mVirtualDisplay used to skip the
        // early return on a second stop and release a codec that a pending
        // consent would later try to start ("codec is released already" crash).
        mMediaCodec = null;
        // Sharing ended: drop the "device is remotely controlled" notification,
        // otherwise it would stay visible until the process dies.
        stopForeground(true);
        if (destroyMediaProjection) {
            destroyMediaProjection();
        }
    }

    private void destroyMediaProjection() {
        if (mMediaProjection != null) {
            if (mMediaProjectionCallback != null) {
                mMediaProjection.unregisterCallback(mMediaProjectionCallback);
                mMediaProjectionCallback = null;
            }
            mMediaProjection.stop();
            mMediaProjection = null;
        }
        Log.i(Const.LOG_TAG, "MediaProjection Stopped");
    }

    private void startSending() {
        MediaCodecInputStream mcis = new MediaCodecInputStream(mMediaCodec);
        mPacketizer.setInputStream(mcis);
        mcis.setH264Packetizer((H264Packetizer) mPacketizer);
        mPacketizer.start();
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(Const.ACTION_SCREEN_SHARING_START));
    }

    private VirtualDisplay createVirtualDisplay() {
        return mMediaProjection.createVirtualDisplay("MainActivity",
                mScreenWidth, mScreenHeight, mScreenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mInputSurface, null /*Callbacks*/, null
                /*Handler*/);
    }

    private boolean initRecorder() {
        try {
            mMediaCodec = MediaCodec.createEncoderByType(MIME_TYPE_VIDEO);
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }

        MediaFormat mediaFormat = MediaFormat.createVideoFormat(MIME_TYPE_VIDEO, mScreenWidth, mScreenHeight);
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, mVideoBitrate);
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, mVideoFrameRate);
        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        // This method call may throw CodecException!
        try {
            mMediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        } catch (Exception e) {
            Log.e(Const.LOG_TAG, "Failed to configure codec with parameters: screenWidth=" + mScreenWidth +
                    ", screenHeight=" + mScreenHeight + ", bitrate=" + mVideoBitrate + ", frameRate=" + mVideoFrameRate +
                    ", colorFormat=" + MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface + ", frameInterval=1");
            e.printStackTrace();
            return false;
        }
        mInputSurface = mMediaCodec.createInputSurface();
        return true;
    }

}