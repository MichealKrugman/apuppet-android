package com.hmdm.control;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

// Headless design: the ConnectionSupervisor service owns the engine's
// EventListener/StateListener slots. The Activity is a UI/consent shell that
// renders from the supervisor's broadcasts (ACTION_ENGINE_STATE /
// ACTION_ADMIN_CHANGED) and only appears for permission prompts.
public class MainActivity extends AppCompatActivity {

    // ConnectionSupervisor -> UI: admin name changed (null = nobody in room).
    public static final String ACTION_ADMIN_CHANGED = "com.hmdm.control.ADMIN_CHANGED";
    public static final String EXTRA_ADMIN_NAME = "admin_name";
    // Full-screen intent action: an admin is waiting for screen-capture
    // consent; launching the Activity with this re-asks the system dialog.
    public static final String ACTION_CONSENT = "com.hmdm.control.CONSENT";

    private ImageView imageViewConnStatus;
    private TextView textViewConnStatus;
    private EditText editTextSessionId;
    private EditText editTextPassword;
    private TextView textViewComment;
    private TextView textViewConnect;
    private TextView textViewSendLink;
    private TextView textViewExit;

    private ImageView overlayDot;
    private Handler handler = new Handler();
    private int overlayDotAlpha;
    private int overlayDotDirection = 1;

    private Dialog exitOnIdleDialog;
    private int exitCounter;
    private static final int EXIT_PROMPT_SEC = 10;

    private static final int OVERLAY_DOT_ANIMATION_INCREMENT = 20;
    private static final int OVERLAY_DOT_ANIMATION_DELAY = 200;

    private SharingEngine sharingEngine;

    private SettingsHelper settingsHelper;

    private String sessionId;
    private String password;
    private String adminName;

    private final static String ATTR_SESSION_ID = "sessionId";
    private final static String ATTR_PASSWORD = "password";
    private final static String ATTR_ADMIN_NAME = "adminName";

    private boolean needReconnect = false;

    // True between onResume/onPause: startActivityForResult for the consent
    // dialog is only safe from the foreground.
    private boolean isResumed = false;
    // Set when the Activity was launched by the consent notification
    // (ACTION_CONSENT); onResume re-asks the screen-capture dialog.
    private boolean consentKick = false;

    private MediaProjectionManager projectionManager;

    private BroadcastReceiver mSharingServiceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) {
                return;
            }
            if (intent.getAction().equals(Const.ACTION_SCREEN_SHARING_START)) {
                notifySharingStart();

            } else if (intent.getAction().equals(Const.ACTION_SCREEN_SHARING_STOP)) {
                notifySharingStop();
                adminName = null;
                updateUI();
                scheduleExitOnIdle();

            } else if (intent.getAction().equals(Const.ACTION_SCREEN_SHARING_FAILED)) {
                String message = intent.getStringExtra(Const.EXTRA_MESSAGE);
                if (message != null) {
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                }
                adminName = null;
                updateUI();
                scheduleExitOnIdle();

            } else if (intent.getAction().equals(Const.ACTION_CONNECTION_FAILURE)) {
                sharingEngine.setState(Const.STATE_DISCONNECTED);
                Toast.makeText(MainActivity.this, R.string.connection_failure_hint, Toast.LENGTH_LONG).show();
                updateUI();

            } else if (intent.getAction().equals(Const.ACTION_SCREEN_SHARING_PERMISSION_NEEDED)) {
                if (isResumed) {
                    // Foreground: show the system consent dialog directly and
                    // tell the supervisor not to post its notification.
                    ConnectionSupervisor.consumeConsentPending();
                    ConnectionSupervisor.cancelConsentNotification(MainActivity.this);
                    startActivityForResult(projectionManager.createScreenCaptureIntent(), Const.REQUEST_SCREEN_SHARE);
                } else {
                    // Backgrounded: Android blocks activity starts from here.
                    // Post the full-screen consent notification instead; tapping
                    // it (or the screen turning on) launches this Activity with
                    // ACTION_CONSENT, which re-asks in onResume.
                    ConnectionSupervisor.showConsentNotification(MainActivity.this, adminName);
                }

            } else if (intent.getAction().equals(Const.ACTION_ENGINE_STATE)) {
                updateUI();

            } else if (intent.getAction().equals(ACTION_ADMIN_CHANGED)) {
                adminName = intent.getStringExtra(EXTRA_ADMIN_NAME);
                updateUI();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (savedInstanceState != null) {
            restoreInstanceState(savedInstanceState);
        }

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        settingsHelper = SettingsHelper.getInstance(this);
        sharingEngine = SharingEngineFactory.getSharingEngine();
        // NOTE: engine listeners (EventListener/StateListener) are owned by
        // ConnectionSupervisor, never by this Activity — otherwise a background
        // Activity recreate would steal them from the supervisor.

        DisplayMetrics metrics = new DisplayMetrics();
        ScreenSharingHelper.getRealScreenSize(this, metrics);
        float videoScale = ScreenSharingHelper.adjustScreenMetrics(metrics);
        settingsHelper.setFloat(SettingsHelper.KEY_VIDEO_SCALE, videoScale);
        ScreenSharingHelper.setScreenMetrics(this, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi);

        sharingEngine.setScreenWidth(metrics.widthPixels);
        sharingEngine.setScreenHeight(metrics.heightPixels);

        IntentFilter intentFilter = new IntentFilter(Const.ACTION_SCREEN_SHARING_START);
        intentFilter.addAction(Const.ACTION_SCREEN_SHARING_STOP);
        intentFilter.addAction(Const.ACTION_SCREEN_SHARING_PERMISSION_NEEDED);
        intentFilter.addAction(Const.ACTION_SCREEN_SHARING_FAILED);
        intentFilter.addAction(Const.ACTION_CONNECTION_FAILURE);
        // Supervisor -> UI state/admin updates.
        intentFilter.addAction(Const.ACTION_ENGINE_STATE);
        intentFilter.addAction(ACTION_ADMIN_CHANGED);
        LocalBroadcastManager.getInstance(this).registerReceiver(mSharingServiceReceiver, intentFilter);
        ConnectionSupervisor.setActivityAlive(true);

        projectionManager = (MediaProjectionManager)getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        handleConsentIntent(getIntent());

        initUI();
        setDefaultSettings();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleConsentIntent(intent);
    }

    private void handleConsentIntent(Intent intent) {
        if (intent != null && ACTION_CONSENT.equals(intent.getAction())) {
            consentKick = true;
            String admin = intent.getStringExtra(EXTRA_ADMIN_NAME);
            if (admin != null) {
                adminName = admin;
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        isResumed = true;
        maybeAskNotificationPermission();
        // Pairing identifiers for the UI / "send link" action (the supervisor
        // does the actual connecting).
        sessionId = settingsHelper.getString(SettingsHelper.KEY_ROOM_ID);
        password = BuildConfig.DEFAULT_ROOM_PIN;
        // If a consent prompt is outstanding (launched here from the
        // notification, or re-entering the app while an admin waits), re-ask
        // the system dialog now that we are in the foreground.
        if (consentKick || ConnectionSupervisor.consumeConsentPending()) {
            consentKick = false;
            ConnectionSupervisor.cancelConsentNotification(this);
            ScreenSharingHelper.requestSharing(this);
        }
        updateUI();

        startService(new Intent(MainActivity.this, GestureDispatchService.class));
        if (!Utils.isAccessibilityPermissionGranted(this)) {
            textViewConnect.setVisibility(View.INVISIBLE);
            new AlertDialog.Builder(this)
                    .setMessage(R.string.accessibility_hint)
                    .setPositiveButton(R.string.continue_button, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            Intent intent = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
                            startActivityForResult(intent, 0);
                        }
                    })
                    .setCancelable(false)
                    .create()
                    .show();
        } else {
            configureAndConnect();
        }
    }

    @Override
    protected void onPause() {
        isResumed = false;
        super.onPause();
    }

    /**
     * API 33+: notifications are the channel the headless consent prompt
     * comes through (full-screen intent when an admin joins while the device
     * is idle). Ask exactly once — this is a permission popup, which is the
     * only UI the headless agent is supposed to show.
     */
    private void maybeAskNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && !settingsHelper.getBoolean(SettingsHelper.KEY_NOTIFICATIONS_ASKED)
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            settingsHelper.setBoolean(SettingsHelper.KEY_NOTIFICATIONS_ASKED, true);
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                    Const.REQUEST_PERMISSION_NOTIFICATIONS);
        }
    }

    private void configureAndConnect() {
        if (settingsHelper.getString(SettingsHelper.KEY_SERVER_URL) == null) {
            // Not configured yet
            settingsHelper.setString(SettingsHelper.KEY_SERVER_URL, BuildConfig.DEFAULT_SERVER_URL);
            settingsHelper.setString(SettingsHelper.KEY_SECRET, BuildConfig.DEFAULT_SECRET);
            settingsHelper.setBoolean(SettingsHelper.KEY_USE_DEFAULT, !BuildConfig.DEFAULT_SECRET.equals(""));
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivityForResult(intent, Const.REQUEST_SETTINGS);
            return;
        }

        // The supervisor owns the connection: started on every resume, and
        // explicitly told to rebuild the session after settings changes.
        if (needReconnect) {
            needReconnect = false;
            ConnectionSupervisor.reconnect(this);
        } else {
            ConnectionSupervisor.start(this);
        }
    }

    @Override
    public void onDestroy() {
        ConnectionSupervisor.setActivityAlive(false);
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(mSharingServiceReceiver);
        } catch (Exception e) {
        }
        super.onDestroy();
    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        savedInstanceState.putString(ATTR_SESSION_ID, sessionId);
        savedInstanceState.putString(ATTR_PASSWORD, password);
        savedInstanceState.putString(ATTR_ADMIN_NAME, adminName);
        super.onSaveInstanceState(savedInstanceState);
    }

    private void restoreInstanceState(Bundle savedInstanceState) {
        sessionId = savedInstanceState.getString(ATTR_SESSION_ID);
        password = savedInstanceState.getString(ATTR_PASSWORD);
        adminName = savedInstanceState.getString(ATTR_ADMIN_NAME);
    }

    @Override
    public void onBackPressed() {
        Toast.makeText(this, R.string.back_pressed, Toast.LENGTH_LONG).show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        if (id == R.id.action_settings) {
            if (adminName != null) {
                Toast.makeText(this, R.string.settings_unavailable, Toast.LENGTH_LONG).show();
                return true;
            }
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivityForResult(intent, Const.REQUEST_SETTINGS);
            cancelExitOnIdle();
            return true;
        } else if (id == R.id.action_about) {
            showAbout();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == Const.REQUEST_SETTINGS) {
            if (resultCode == Const.RESULT_DIRTY) {
                needReconnect = true;
            } else {
                scheduleExitOnIdle();
            }
        } else if (requestCode == Const.REQUEST_SCREEN_SHARE) {
            if (resultCode != RESULT_OK) {
                Toast.makeText(this, R.string.screen_cast_denied, Toast.LENGTH_LONG).show();
                adminName = null;
                updateUI();
                // Drop the ping watchdog / pending prompt on the supervisor
                // side too, so a denial does not reconnect the session later.
                ConnectionSupervisor.onConsentDenied(this);
                scheduleExitOnIdle();
            } else {
                ScreenSharingHelper.startSharing(this, resultCode, data);
            }
        }
    }

    private void initUI() {
        imageViewConnStatus = findViewById(R.id.image_conn_status);
        textViewConnStatus = findViewById(R.id.conn_status);
        editTextSessionId = findViewById(R.id.session_id_edit);
        editTextPassword = findViewById(R.id.password_edit);
        textViewComment = findViewById(R.id.comment);
        textViewConnect = findViewById(R.id.reconnect);
        textViewSendLink = findViewById(R.id.send_link);
        textViewExit = findViewById(R.id.disconnect_exit);

        textViewConnect.setOnClickListener(v -> connect());

        textViewSendLink.setOnClickListener(v -> sendLink());

        textViewExit.setOnClickListener(v -> gracefulExit());
    }

    private void gracefulExit() {
        // "Exit" closes the UI only: the headless ConnectionSupervisor keeps
        // the agent running and connected (it would also restart via
        // START_STICKY, so killing the process here would just churn).
        if (adminName != null) {
            notifySharingStop();
            ScreenSharingHelper.stopSharing(MainActivity.this, true);
        }
        finishAffinity();
    }

    private void updateUI() {
        int[] stateLabels = {R.string.state_disconnected, R.string.state_connecting, R.string.state_connected, R.string.state_sharing, R.string.state_disconnecting};
        int[] stateImages = {R.drawable.ic_disconnected, R.drawable.ic_connecting, R.drawable.ic_connected, R.drawable.ic_sharing, R.drawable.ic_connecting};

        int state = sharingEngine.getState();
        if (state == Const.STATE_CONNECTED && adminName != null) {
            imageViewConnStatus.setImageDrawable(getDrawable(stateImages[Const.STATE_SHARING]));
            textViewConnStatus.setText(stateLabels[Const.STATE_SHARING]);
        } else {
            imageViewConnStatus.setImageDrawable(getDrawable(stateImages[state]));
            textViewConnStatus.setText(stateLabels[state]);
        }
        String serverUrl = Utils.prepareDisplayUrl(settingsHelper.getString(SettingsHelper.KEY_SERVER_URL));

        textViewSendLink.setVisibility(state == Const.STATE_CONNECTED ? View.VISIBLE : View.INVISIBLE);
        textViewConnect.setVisibility(state == Const.STATE_DISCONNECTED ? View.VISIBLE : View.INVISIBLE);
        switch (state) {
            case Const.STATE_DISCONNECTED:
                editTextSessionId.setText("");
                editTextPassword.setText("");
                if (sharingEngine.getErrorReason() != null) {
                    textViewComment.setText(getString(R.string.hint_connection_error, serverUrl));
                }
                break;
            case Const.STATE_CONNECTING:
                textViewComment.setText(getString(R.string.hint_connecting, serverUrl));
                break;
            case Const.STATE_DISCONNECTING:
                textViewComment.setText(getString(R.string.hint_disconnecting));
                break;
            case Const.STATE_CONNECTED:
                editTextSessionId.setText(sessionId);
                editTextPassword.setText(password);
                textViewComment.setText(adminName != null ?
                        getString(R.string.hint_sharing, adminName) :
                        getString(R.string.hint_connected, serverUrl)
                        );
                break;
        }
    }

    private void setDefaultSettings() {
        if (settingsHelper.getString(SettingsHelper.KEY_DEVICE_NAME) == null) {
            settingsHelper.setString(SettingsHelper.KEY_DEVICE_NAME, Build.MANUFACTURER + " " + Build.MODEL);
        }
        if (settingsHelper.getString(SettingsHelper.KEY_ROOM_ID) == null) {
            // Per-install random room name: stable for this install, unique
            // across devices so two identical models cannot collide. Not the
            // raw device name (that is shared by every unit of a model).
            settingsHelper.setString(SettingsHelper.KEY_ROOM_ID, "room-" + Utils.randomString(10, false));
        }
        if (settingsHelper.getInt(SettingsHelper.KEY_BITRATE) == 0) {
            settingsHelper.setInt(SettingsHelper.KEY_BITRATE, Const.DEFAULT_BITRATE);
        }
        if (settingsHelper.getInt(SettingsHelper.KEY_FRAME_RATE) == 0) {
            settingsHelper.setInt(SettingsHelper.KEY_FRAME_RATE, Const.DEFAULT_FRAME_RATE);
        }
        // Headless agent: idle auto-exit stays disabled no matter what was
        // stored or what the settings screen wrote. The supervisor also
        // re-forces this on every start command.
        settingsHelper.setInt(SettingsHelper.KEY_IDLE_TIMEOUT, Const.DEFAULT_IDLE_TIMEOUT);
        if (settingsHelper.getInt(SettingsHelper.KEY_PING_TIMEOUT) == 0) {
            settingsHelper.setInt(SettingsHelper.KEY_PING_TIMEOUT, Const.DEFAULT_PING_TIMEOUT);
        }
    }

    private void sendLink() {
        String url = settingsHelper.getString(SettingsHelper.KEY_SERVER_URL);
        url += "?session=" + sessionId + "&pin=" + password;
        try {
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.send_link_subject));
            String shareMessage= getString(R.string.send_link_message, url, settingsHelper.getString(SettingsHelper.KEY_DEVICE_NAME));
            shareIntent.putExtra(Intent.EXTRA_TEXT, shareMessage);
            startActivity(Intent.createChooser(shareIntent, getString(R.string.send_link_chooser)));
        } catch(Exception e) {
            e.printStackTrace();
            Toast.makeText(this, R.string.send_link_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void showAbout() {
        ImageView imageView = new ImageView(this);
        imageView.setImageDrawable(getResources().getDrawable(R.mipmap.ic_launcher));
        new AlertDialog.Builder(this)
                .setTitle(R.string.about_title)
                .setMessage(getString(R.string.about_message, BuildConfig.VERSION_NAME, BuildConfig.VARIANT))
                .setPositiveButton(R.string.ok, (dialog, which) -> dialog.dismiss())
                .create()
                .show();
    }

    private void connect() {
        // Pairing is resolved from the install itself: the room is a per-install
        // random id (so two identical phones cannot collide) and the PIN is
        // baked in at build time. No session id or password is shown in the UI.
        // The connection itself is owned by ConnectionSupervisor; this button
        // just asks it to rebuild the session.
        sessionId = settingsHelper.getString(SettingsHelper.KEY_ROOM_ID);
        password = BuildConfig.DEFAULT_ROOM_PIN;
        ConnectionSupervisor.reconnect(this);
    }

    // NOTE: onStartSharing/onStopSharing/onPing/onRemoteControlEvent/
    // onSharingApiStateChanged used to live here. ConnectionSupervisor now
    // owns both engine listener slots; this Activity learns about the same
    // events through ACTION_ADMIN_CHANGED / ACTION_ENGINE_STATE broadcasts.

    private void scheduleExitOnIdle() {
        int exitOnIdleTimeout = settingsHelper.getInt(SettingsHelper.KEY_IDLE_TIMEOUT);
        if (exitOnIdleTimeout > 0) {
            exitCounter = EXIT_PROMPT_SEC;
            handler.postDelayed(warningOnIdleRunnable, exitOnIdleTimeout * 1000);
            Log.d(Const.LOG_TAG, "Scheduling exit in " + (exitOnIdleTimeout * 1000) + " sec");
        }
    }

    private void cancelExitOnIdle() {
        Log.d(Const.LOG_TAG, "Cancelling scheduled exit");
        handler.removeCallbacks(warningOnIdleRunnable);
        handler.removeCallbacks(exitRunnable);
    }

    private Runnable exitRunnable = () -> {
        exitCounter--;
        if (exitCounter > 0) {
            TextView messageView = exitOnIdleDialog.findViewById(android.R.id.message);
            if (messageView != null) {
                messageView.setText(MainActivity.this.getResources().getString(R.string.app_idle_warning, exitCounter));
            }
            scheduleExitRunnable();

        } else {
            gracefulExit();
        }
    };

    private Runnable warningOnIdleRunnable = () -> {
         exitOnIdleDialog = new AlertDialog.Builder(MainActivity.this)
                .setMessage(MainActivity.this.getResources().getString(R.string.app_idle_warning, exitCounter))
                .setPositiveButton(R.string.button_exit, (dialog1, which) -> {
                    gracefulExit();
                })
                .setNegativeButton(R.string.button_keep_idle, (dialog1, which) -> {
                    scheduleExitOnIdle();
                    handler.removeCallbacks(exitRunnable);
                    dialog1.dismiss();
                })
                .setCancelable(false)
                .create();
        exitOnIdleDialog.show();
        scheduleExitRunnable();
    };

    private void scheduleExitRunnable() {
        handler.postDelayed(exitRunnable, 1000);
    }

    // NOTE: the sharing ping-timeout watchdog (scheduleSharingTimeout /
    // cancelSharingTimeout / sharingStopByPingTimeoutRunnable) moved into
    // ConnectionSupervisor so it runs with no Activity alive.

    private Runnable overlayDotRunnable = new Runnable() {
        @Override
        public void run() {
            if (overlayDotDirection == 0) {
                return;
            }
            overlayDotAlpha += OVERLAY_DOT_ANIMATION_INCREMENT * overlayDotDirection;
            if (overlayDotAlpha > 255) {
                overlayDotAlpha = 255;
                overlayDotDirection = -overlayDotDirection;
            }
            if (overlayDotAlpha < 128) {
                overlayDotAlpha = 128;
                overlayDotDirection = -overlayDotDirection;
            }
            overlayDot.setImageAlpha(overlayDotAlpha);
            handler.postDelayed(overlayDotRunnable, OVERLAY_DOT_ANIMATION_DELAY);
        }
    };

    private void notifySharingStart() {
        notifyGestureService(Const.ACTION_SCREEN_SHARING_START);
        if (settingsHelper.getBoolean(SettingsHelper.KEY_NOTIFY_SHARING)) {
            // Show a flashing dot
            Utils.lockDeviceRotation(this, true);
            overlayDot = createOverlayDot();
            overlayDotAlpha = 0;
            overlayDotDirection = 1;
            handler.postDelayed(overlayDotRunnable, OVERLAY_DOT_ANIMATION_DELAY);

        } else {
            // Just show some dialog to trigger the traffic
            final AlertDialog dialog = new AlertDialog.Builder(MainActivity.this)
                    .setMessage(R.string.share_start_text)
                    .setPositiveButton(R.string.ok, (dialog1, which) -> dialog1.dismiss())
                    .create();
            dialog.show();
            handler.postDelayed(() -> {
                if (dialog != null && dialog.isShowing()) {
                    try {
                        dialog.dismiss();
                    } catch (Exception e) {
                    }
                }
            }, 3000);
        }
    }

    private void notifySharingStop() {
        notifyGestureService(Const.ACTION_SCREEN_SHARING_STOP);
        if (settingsHelper.getBoolean(SettingsHelper.KEY_NOTIFY_SHARING)) {
            overlayDotDirection = 0;
            if (overlayDot != null) {
                WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
                wm.removeView(overlayDot);
                overlayDot = null;
            }
            Utils.lockDeviceRotation(this, false);
        }
    }

    private void notifyGestureService(String action) {
        Intent intent = new Intent(MainActivity.this, GestureDispatchService.class);
        intent.setAction(action);
        startService(intent);
    }

    public ImageView createOverlayDot() {
        int size = getResources().getDimensionPixelOffset(R.dimen.overlay_dot_size);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(size, size,
                Utils.OverlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        |WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        |WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.LEFT | Gravity.TOP;
        params.x = getResources().getDimensionPixelOffset(R.dimen.overlay_dot_offset);
        params.y = getResources().getDimensionPixelOffset(R.dimen.overlay_dot_offset);

        ImageView view = new ImageView(this);
        view.setImageResource(R.drawable.flash_dot);
        view.setImageAlpha(0);
        WindowManager wm = (WindowManager)getSystemService(Context.WINDOW_SERVICE);
        wm.addView(view, params);
        return view;
    }
}
