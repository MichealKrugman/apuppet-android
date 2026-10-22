package com.hmdm.control;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

/**
 * Persistent foreground service that owns the Janus connection lifecycle.
 *
 * The Activity is only a thin UI/consent shell; everything needed to *stay*
 * connected lives here so it survives Activity destruction, screen-off,
 * network changes and device reboots:
 *
 *  - Keeps the process alive as a foreground service (specialUse type),
 *    started by BootReceiver after reboot and kept alive via START_STICKY.
 *  - Registers a default-network callback: on network loss/restore or a
 *    Wi-Fi<->cellular interface switch, it tears down and reconnects.
 *  - Reconnects with exponential backoff whenever the engine drops
 *    unexpectedly (and whenever JanusSessionPollService declares the
 *    long-poll session dead).
 *  - Dispatches remote-control gestures headlessly (no Activity required).
 *  - Drives the MediaProjection consent prompt: if the Activity is alive it
 *    shows the system dialog directly; if not, a full-screen-intent
 *    notification launches it so an admin join can pop the approve dialog
 *    even when the device is locked. Popups only for permissions.
 */
public class ConnectionSupervisor extends Service {
    private static final String TAG = Const.LOG_TAG;

    private static final String CHANNEL_ID = "com.hmdm.control.connection";
    private static final String CONSENT_CHANNEL_ID = "com.hmdm.control.consent";
    private static final int NOTIFICATION_ID = 110;

    // Guards the CONNECTING state: if the HTTP stage succeeds but WebRTC ICE
    // (or the datachannel open) never completes, no completion handler ever
    // fires and nothing would schedule a reconnect. Normal connects take
    // ~8-25s; anything still CONNECTING after this is stalled and gets torn
    // down + retried with the usual flow.
    private static final long CONNECT_TIMEOUT_MS = 90_000L;

    // True while the consent prompt must be shown even though the Activity
    // exists but is backgrounded; also set by whoever posts the consent
    // notification so a later Activity resume re-triggers the request.
    private static volatile boolean consentPending = false;
    // Set by the Activity in onCreate/onDestroy. When true the Activity's own
    // receiver handles PERMISSION_NEEDED, so the supervisor must not also post
    // a consent notification (that would double-prompt).
    private static volatile boolean activityAlive = false;

    private static volatile ConnectionSupervisor instance = null;

    private SharingEngine engine;
    private SettingsHelper settingsHelper;
    private Handler handler;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private Network lastNetwork;

    private boolean registered = false;
    private int reconnectAttempt = 0;

    // The admin currently in the text room (null = nobody). Drives the ping
    // watchdog and the consent notification text.
    private volatile String currentAdmin = null;

    // Fired by JanusSessionPollService when the long-poll has failed repeatedly.
    private final BroadcastReceiver sessionLostReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int state = engine.getState();
            Log.i(TAG, "session lost broadcast; engine state=" + state);
            if (state == Const.STATE_DISCONNECTED) {
                connect("session lost");
            } else {
                disconnectThenConnect("session lost");
            }
        }
    };

    // ScreenSharingService reports sharing lifecycle via LocalBroadcastManager.
    // The supervisor forwards these to the accessibility gesture service (so
    // gestures are injected even with no Activity alive) and manages the
    // consent/ping state that used to live in the Activity.
    private final BroadcastReceiver sharingReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Const.ACTION_SCREEN_SHARING_START.equals(action)) {
                sendGestureAction(Const.ACTION_SCREEN_SHARING_START);
                // Sharing is live: any pending consent request is satisfied.
                consentPending = false;
                cancelConsentNotification(ConnectionSupervisor.this);
            } else if (Const.ACTION_SCREEN_SHARING_STOP.equals(action)) {
                sendGestureAction(Const.ACTION_SCREEN_SHARING_STOP);
                currentAdmin = null;
                consentPending = false;
                cancelPingWatchdog();
                cancelConsentNotification(ConnectionSupervisor.this);
                broadcastAdmin(null);
            } else if (Const.ACTION_SCREEN_SHARING_FAILED.equals(action)) {
                currentAdmin = null;
                consentPending = false;
                cancelPingWatchdog();
                cancelConsentNotification(ConnectionSupervisor.this);
                broadcastAdmin(null);
            } else if (Const.ACTION_SCREEN_SHARING_PERMISSION_NEEDED.equals(action)) {
                if (!activityAlive) {
                    // Nobody is here to show the system dialog. Post the
                    // full-screen consent notification; tapping it (or the
                    // screen turning on) launches the Activity, which re-asks
                    // via consumeConsentPending() in onResume.
                    showConsentNotification(ConnectionSupervisor.this, currentAdmin);
                }
                // If the Activity is alive its own receiver shows the dialog.
            }
        }
    };

    private final Runnable reconnectRunnable = new Runnable() {
        @Override
        public void run() {
            connect("scheduled reconnect");
        }
    };

    // Fires if the engine is still CONNECTING after CONNECT_TIMEOUT_MS.
    private final Runnable connectWatchdogRunnable = new Runnable() {
        @Override
        public void run() {
            if (engine.getState() != Const.STATE_CONNECTING) {
                return;
            }
            Log.w(TAG, "connect watchdog: still CONNECTING after " + CONNECT_TIMEOUT_MS
                    + "ms (ICE/datachannel stalled); rebuilding session");
            disconnectThenConnect("connect watchdog");
        }
    };

    // Mirrors the old MainActivity behaviour: if an admin joined but no ping
    // arrived within KEY_PING_TIMEOUT seconds (admin browser crashed, or the
    // consent was never answered), stop sharing and rebuild the session so the
    // admin can rejoin cleanly.
    private final Runnable pingWatchdogRunnable = new Runnable() {
        @Override
        public void run() {
            if (currentAdmin == null) {
                return;
            }
            Log.i(TAG, "ping timeout: admin '" + currentAdmin + "' went silent, rebuilding session");
            sendGestureAction(Const.ACTION_SCREEN_SHARING_STOP);
            ScreenSharingHelper.stopSharing(getApplicationContext(), false);
            currentAdmin = null;
            consentPending = false;
            cancelConsentNotification(ConnectionSupervisor.this);
            broadcastAdmin(null);
            disconnectThenConnect("ping timeout");
        }
    };

    // ---- static entry points -------------------------------------------------

    /** Start (or ensure running of) the supervisor. Safe to call repeatedly. */
    public static void start(Context context) {
        Intent intent = new Intent(context, ConnectionSupervisor.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    /** Force a full teardown + reconnect (e.g. after settings changed). */
    public static void reconnect(Context context) {
        Intent intent = new Intent(context, ConnectionSupervisor.class);
        intent.setAction(ACTION_RECONNECT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    /** Called by the Activity on create/destroy so the supervisor knows
     *  whether its own receiver is registered for PERMISSION_NEEDED. */
    public static void setActivityAlive(boolean alive) {
        activityAlive = alive;
    }

    /** Returns true (and clears) if a consent request is still outstanding. */
    public static boolean consumeConsentPending() {
        boolean pending = consentPending;
        consentPending = false;
        return pending;
    }

    /**
     * Post the full-screen consent notification. Also marks consent as
     * pending so a later Activity resume re-triggers the request if the
     * notification is dismissed without being used.
     */
    public static void showConsentNotification(Context context, String adminName) {
        consentPending = true;
        ensureChannel(context);
        Intent intent = new Intent(context, MainActivity.class);
        intent.setAction(MainActivity.ACTION_CONSENT);
        intent.putExtra(MainActivity.EXTRA_ADMIN_NAME, adminName);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT;
        PendingIntent pendingIntent = PendingIntent.getActivity(context, Const.REQUEST_SCREEN_SHARE, intent, piFlags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CONSENT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(adminName != null ? adminName + " requests screen access" : "Screen access requested")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setFullScreenIntent(pendingIntent, true);
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(Const.REQUEST_SCREEN_SHARE, builder.build());
        }
        Log.i(TAG, "consent notification posted (admin=" + adminName + ")");
    }

    public static void cancelConsentNotification(Context context) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(Const.REQUEST_SCREEN_SHARE);
        }
    }

    /**
     * The user denied screen capture. Mirror the old Activity behaviour:
     * drop the sharing state so the ping watchdog stops rescheduling, and
     * dismiss any prompt. The engine stays connected (the admin can re-ask
     * by rejoining).
     */
    public static void onConsentDenied(Context context) {
        consentPending = false;
        cancelConsentNotification(context);
        ConnectionSupervisor self = instance;
        if (self != null) {
            self.currentAdmin = null;
            self.cancelPingWatchdog();
            self.broadcastAdmin(null);
        }
    }

    private static final String ACTION_RECONNECT = "com.hmdm.control.action.RECONNECT";

    // ---- lifecycle -----------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "ConnectionSupervisor created");
        instance = this;
        handler = new Handler(Looper.getMainLooper());
        engine = SharingEngineFactory.getSharingEngine();
        settingsHelper = SettingsHelper.getInstance(this);
        engine.setStateListener(stateListener);
        engine.setEventListener(eventListener);
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        IntentFilter lostFilter = new IntentFilter(Const.ACTION_CONNECTION_LOST);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(sessionLostReceiver, lostFilter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(sessionLostReceiver, lostFilter);
        }

        IntentFilter sharingFilter = new IntentFilter(Const.ACTION_SCREEN_SHARING_START);
        sharingFilter.addAction(Const.ACTION_SCREEN_SHARING_STOP);
        sharingFilter.addAction(Const.ACTION_SCREEN_SHARING_FAILED);
        sharingFilter.addAction(Const.ACTION_SCREEN_SHARING_PERMISSION_NEEDED);
        LocalBroadcastManager.getInstance(this).registerReceiver(sharingReceiver, sharingFilter);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startAsForeground();
        registerNetworkCallback();
        registerDisplayListener();

        // The idle auto-exit is force-disabled here regardless of any stored
        // setting: the agent must never self-terminate.
        settingsHelper.setInt(SettingsHelper.KEY_IDLE_TIMEOUT, 0);

        if (intent != null && ACTION_RECONNECT.equals(intent.getAction())) {
            handler.post(() -> {
                if (engine.getState() == Const.STATE_DISCONNECTED) {
                    connect("explicit reconnect");
                } else {
                    disconnectThenConnect("explicit reconnect");
                }
            });
        } else {
            connect("supervisor start");
        }
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "ConnectionSupervisor destroyed");
        instance = null;
        handler.removeCallbacksAndMessages(null);
        if (displayListenerRegistered) {
            try {
                DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
                if (dm != null) {
                    dm.unregisterDisplayListener(displayListener);
                }
            } catch (Exception ignored) {
            }
            displayListenerRegistered = false;
        }
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {
            }
        }
        try {
            unregisterReceiver(sessionLostReceiver);
        } catch (Exception ignored) {
        }
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(sharingReceiver);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    // ---- connection ----------------------------------------------------------

    private void connect(String why) {
        if (settingsHelper.getString(SettingsHelper.KEY_SERVER_URL) == null) {
            // First-run: not configured yet. The Activity opens SettingsActivity;
            // it will call start()/reconnect() once a server is chosen.
            Log.i(TAG, "connect(" + why + ") skipped: server not configured yet");
            return;
        }
        int state = engine.getState();
        if (state != Const.STATE_DISCONNECTED) {
            Log.i(TAG, "connect(" + why + ") ignored: state=" + state);
            return;
        }
        handler.removeCallbacks(reconnectRunnable);

        applyScreenMetrics();

        String sessionId = settingsHelper.getString(SettingsHelper.KEY_ROOM_ID);
        String password = BuildConfig.DEFAULT_ROOM_PIN;
        String deviceName = settingsHelper.getString(SettingsHelper.KEY_DEVICE_NAME);
        engine.setUsername(deviceName != null ? deviceName : "Device");

        Log.i(TAG, "connect(" + why + "): room=" + sessionId);
        expectingDisconnect = false;
        engine.connect(getApplicationContext(), sessionId, password, (success, errorReason) -> {
            if (success) {
                reconnectAttempt = 0;
                Log.i(TAG, "connected to room " + sessionId);
            } else {
                Log.w(TAG, "connect failed: " + errorReason);
                scheduleReconnect();
            }
        });
        // Cover the stall case: if ICE/datachannel never completes, no
        // completion handler will ever run to schedule the next attempt.
        handler.removeCallbacks(connectWatchdogRunnable);
        handler.postDelayed(connectWatchdogRunnable, CONNECT_TIMEOUT_MS);
    }

    // Set while we intentionally disconnect (to reconnect) so the StateListener
    // does not treat it as an unexpected drop and schedule a second reconnect.
    private static volatile boolean expectingDisconnect = false;

    private void disconnectThenConnect(String why) {
        expectingDisconnect = true;
        engine.disconnect(getApplicationContext(), (success, errorReason) -> {
            expectingDisconnect = false;
            connect(why);
        });
    }

    private void scheduleReconnect() {
        if (settingsHelper.getString(SettingsHelper.KEY_SERVER_URL) == null) {
            return;
        }
        long delay;
        if (reconnectAttempt <= 0) {
            delay = 5000;
        } else {
            delay = Math.min(5000L << Math.min(reconnectAttempt, 6), 60000L);
        }
        reconnectAttempt++;
        Log.i(TAG, "scheduling reconnect #" + reconnectAttempt + " in " + delay + "ms");
        handler.removeCallbacks(reconnectRunnable);
        handler.postDelayed(reconnectRunnable, delay);
    }

    // Display changes (rotation) must refresh the screen metrics and tell the
    // admin the new resolution even when no Activity is alive: the refresh
    // used to run only from MainActivity.onCreate() or connect(), so a
    // rotation mid-session left the capture at the stale size (the admin saw
    // a stretched picture).
    private boolean displayListenerRegistered;

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
        }

        @Override
        public void onDisplayRemoved(int displayId) {
        }

        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId != Display.DEFAULT_DISPLAY) {
                return;
            }
            // Debounce: a rotation fires several display events (size,
            // density, state) within milliseconds.
            handler.removeCallbacks(displayMetricsRunnable);
            handler.postDelayed(displayMetricsRunnable, 300);
        }
    };

    private final Runnable displayMetricsRunnable = new Runnable() {
        @Override
        public void run() {
            applyScreenMetrics();
            // Re-send "streamingVideoResolution" so the admin's canvas and
            // gesture scale follow the new aspect ratio.
            engine.notifyScreenResolution();
        }
    };

    private void registerDisplayListener() {
        if (displayListenerRegistered) {
            return;
        }
        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) {
            return;
        }
        dm.registerDisplayListener(displayListener, null);
        displayListenerRegistered = true;
        Log.d(TAG, "display listener registered (rotation -> live metrics refresh)");
    }

    private void applyScreenMetrics() {
        DisplayMetrics metrics = new DisplayMetrics();
        ScreenSharingHelper.getRealScreenSize(this, metrics);
        float videoScale = ScreenSharingHelper.adjustScreenMetrics(metrics);
        settingsHelper.setFloat(SettingsHelper.KEY_VIDEO_SCALE, videoScale);
        ScreenSharingHelper.setScreenMetrics(this, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi);
        engine.setScreenWidth(metrics.widthPixels);
        engine.setScreenHeight(metrics.heightPixels);
    }

    // ---- engine listeners ----------------------------------------------------

    private final SharingEngine.StateListener stateListener = new SharingEngine.StateListener() {
        @Override
        public void onSharingApiStateChanged(int state) {
            Log.i(TAG, "engine state -> " + state);
            broadcastState(state);
            updateNotification(state);
            if (state == Const.STATE_CONNECTED) {
                reconnectAttempt = 0;
                handler.removeCallbacks(reconnectRunnable);
                handler.removeCallbacks(connectWatchdogRunnable);
                configureStreaming();
            } else if (state == Const.STATE_DISCONNECTED) {
                handler.removeCallbacks(connectWatchdogRunnable);
                if (!expectingDisconnect) {
                    scheduleReconnect();
                }
            }
        }
    };

    private final SharingEngine.EventListener eventListener = new SharingEngine.EventListener() {
        @Override
        public void onStartSharing(String username) {
            Log.i(TAG, "admin joined: " + username);
            currentAdmin = username;
            consentPending = false;
            broadcastAdmin(username);
            schedulePingWatchdog();
            // Kick the sharing service; it always re-asks for the (single-use)
            // projection token, which broadcasts PERMISSION_NEEDED. The Activity
            // shows the dialog when alive; otherwise the supervisor posts the
            // full-screen consent notification (sharingReceiver above).
            ScreenSharingHelper.requestSharing(getApplicationContext());
        }

        @Override
        public void onStopSharing() {
            Log.i(TAG, "admin left");
            currentAdmin = null;
            consentPending = false;
            cancelPingWatchdog();
            cancelConsentNotification(ConnectionSupervisor.this);
            sendGestureAction(Const.ACTION_SCREEN_SHARING_STOP);
            ScreenSharingHelper.stopSharing(getApplicationContext(), false);
            broadcastAdmin(null);
        }

        @Override
        public void onPing() {
            if (currentAdmin != null) {
                schedulePingWatchdog();
            }
        }

        @Override
        public void onRemoteControlEvent(String event) {
            // Gestures must dispatch even with no Activity alive.
            Intent intent = new Intent(ConnectionSupervisor.this, GestureDispatchService.class);
            intent.setAction(Const.ACTION_GESTURE);
            intent.putExtra(Const.EXTRA_EVENT, event);
            startService(intent);
        }
    };

    private void schedulePingWatchdog() {
        int pingTimeout = settingsHelper.getInt(SettingsHelper.KEY_PING_TIMEOUT);
        if (pingTimeout > 0) {
            handler.removeCallbacks(pingWatchdogRunnable);
            handler.postDelayed(pingWatchdogRunnable, pingTimeout * 1000L);
        }
    }

    private void cancelPingWatchdog() {
        handler.removeCallbacks(pingWatchdogRunnable);
    }

    private void sendGestureAction(String action) {
        Intent intent = new Intent(this, GestureDispatchService.class);
        intent.setAction(action);
        startService(intent);
    }

    private void configureStreaming() {
        String rtpHost = Utils.getRtpUrl(settingsHelper.getString(SettingsHelper.KEY_SERVER_URL));
        int rtpAudioPort = engine.getAudioPort();
        int rtpVideoPort = engine.getVideoPort();
        String testDstIp = settingsHelper.getString(SettingsHelper.KEY_TEST_DST_IP);
        if (testDstIp != null && !testDstIp.trim().equals("")) {
            rtpHost = testDstIp;
            rtpVideoPort = Const.TEST_RTP_PORT;
        }
        ScreenSharingHelper.configure(getApplicationContext(),
                settingsHelper.getBoolean(SettingsHelper.KEY_TRANSLATE_AUDIO),
                settingsHelper.getInt(SettingsHelper.KEY_FRAME_RATE),
                settingsHelper.getInt(SettingsHelper.KEY_BITRATE),
                rtpHost,
                rtpAudioPort,
                rtpVideoPort);
    }

    // ---- network monitoring --------------------------------------------------

    private void registerNetworkCallback() {
        if (registered || connectivityManager == null) {
            return;
        }
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                Log.i(TAG, "network available: " + network);
                handler.post(() -> {
                    if (lastNetwork != null && lastNetwork.equals(network)
                            && engine.getState() != Const.STATE_DISCONNECTED) {
                        // Same network we are already on; nothing to do.
                        return;
                    }
                    lastNetwork = network;
                    // New network or a return from loss: the old Janus session is
                    // bound to the previous interface and is dead. Rebuild it.
                    if (engine.getState() == Const.STATE_DISCONNECTED) {
                        connect("network available");
                    } else {
                        disconnectThenConnect("network changed");
                    }
                });
            }

            @Override
            public void onLost(Network network) {
                Log.i(TAG, "network lost: " + network);
                handler.post(() -> {
                    if (network.equals(lastNetwork)) {
                        lastNetwork = null;
                    }
                    // The poll watchdog will detect the dead session and
                    // reconnect once a network comes back; nothing to do here
                    // besides noting the loss.
                });
            }
        };
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connectivityManager.registerDefaultNetworkCallback(networkCallback);
        } else {
            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            connectivityManager.registerNetworkCallback(request, networkCallback);
        }
        registered = true;
        Log.i(TAG, "network callback registered");
    }

    // ---- broadcasts to UI ----------------------------------------------------

    private void broadcastState(int state) {
        Intent intent = new Intent(Const.ACTION_ENGINE_STATE);
        intent.setPackage(getPackageName());
        intent.putExtra(Const.EXTRA_STATE, state);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void broadcastAdmin(String adminName) {
        Intent intent = new Intent(MainActivity.ACTION_ADMIN_CHANGED);
        intent.setPackage(getPackageName());
        intent.putExtra(MainActivity.EXTRA_ADMIN_NAME, adminName);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    // ---- notification --------------------------------------------------------

    private void startAsForeground() {
        Notification notification = buildNotification(engine != null ? engine.getState() : Const.STATE_DISCONNECTED);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(int state) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(state));
        }
    }

    private Notification buildNotification(int state) {
        ensureChannel(this);
        String text;
        switch (state) {
            case Const.STATE_CONNECTED:
                text = getString(R.string.state_connected);
                break;
            case Const.STATE_SHARING:
                text = getString(R.string.state_sharing);
                break;
            case Const.STATE_CONNECTING:
            case Const.STATE_DISCONNECTING:
                text = getString(R.string.state_connecting);
                break;
            default:
                text = getString(R.string.state_disconnected);
                break;
        }
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, Const.REQUEST_FROM_NOTIFICATION, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel connection = new NotificationChannel(CHANNEL_ID, "Connection",
                        NotificationManager.IMPORTANCE_LOW);
                // HIGH so the consent prompt is a heads-up even with the screen
                // on; the full-screen intent additionally covers screen-off.
                NotificationChannel consent = new NotificationChannel(CONSENT_CHANNEL_ID, "Screen access requests",
                        NotificationManager.IMPORTANCE_HIGH);
                consent.setVibrationPattern(new long[]{0, 400, 200, 400});
                nm.createNotificationChannel(connection);
                nm.createNotificationChannel(consent);
            }
        }
    }
}
