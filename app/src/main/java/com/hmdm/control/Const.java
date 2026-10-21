package com.hmdm.control;

public class Const {
    public static final String LOG_TAG = "com.hmdm.Control";
    public static final int DEFAULT_BITRATE = 256000;
    public static final int DEFAULT_FRAME_RATE = 10;
    // 0 disables the idle auto-exit. The agent must stay connected
    // indefinitely; exiting after N seconds of admin inactivity is the
    // opposite of that, so it is off by default and forced off at startup.
    public static final int DEFAULT_IDLE_TIMEOUT = 0;
    public static final int DEFAULT_PING_TIMEOUT = 20;

    public static final int REQUEST_SETTINGS = 1000;
    public static final int REQUEST_PERMISSION_AUDIO = 1001;
    public static final int REQUEST_SCREEN_SHARE = 1002;
    public static final int REQUEST_FROM_NOTIFICATION = 1003;
    public static final int REQUEST_PERMISSION_OVERLAY = 1004;
    public static final int REQUEST_PERMISSION_NOTIFICATIONS = 1005;
    public static final int RESULT_DIRTY = 2000;

    public static final int MAX_SHARED_SCREEN_WIDTH = 800;
    public static final int MAX_SHARED_SCREEN_HEIGHT = 800;

    public static final int STATE_DISCONNECTED = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_CONNECTED = 2;
    public static final int STATE_SHARING = 3;
    public static final int STATE_DISCONNECTING = 4;

    public static final int CONNECTION_TIMEOUT = 60000;

    public static final String EXTRA_EVENT = "event";
    public static final String EXTRA_SESSION = "session";
    public static final String EXTRA_WEBRTCUP = "webrtcup";
    public static final String EXTRA_MESSAGE = "message";

    public static final int SUCCESS = 0;
    public static final int NETWORK_ERROR = 1;
    public static final int SERVER_ERROR = 2;
    public static final int INTERNAL_ERROR = 3;

    public static final String ACTION_JANUS_SESSION_POLL = "janus_session_poll";
    // Broadcast from JanusSessionPollService when the long-poll has failed
    // repeatedly (network gone or the server destroyed the session). The
    // ConnectionSupervisor reacts by tearing down and reconnecting.
    public static final String ACTION_CONNECTION_LOST = "com.hmdm.control.CONNECTION_LOST";
    // ConnectionSupervisor -> UI: current engine state, for status rendering.
    public static final String ACTION_ENGINE_STATE = "com.hmdm.control.ENGINE_STATE";
    public static final String EXTRA_STATE = "engine_state";

    public static final String JANUS_PLUGIN_TEXTROOM = "janus.plugin.textroom";
    public static final String JANUS_PLUGIN_STREAMING = "janus.plugin.streaming";

    public static final int TEST_RTP_PORT = 1234;

    public static final String ACTION_SCREEN_SHARING_START = "SCREEN_SHARING_START";
    public static final String ACTION_SCREEN_SHARING_STOP = "SCREEN_SHARING_STOP";
    public static final String ACTION_SCREEN_SHARING_PERMISSION_NEEDED = "SCREEN_SHARING_PERMISSION_NEEDED";
    public static final String ACTION_SCREEN_SHARING_FAILED = "SCREEN_SHARING_FAILED";
    public static final String ACTION_CONNECTION_FAILURE = "CONNECTION_FAILURE";
    public static final String ACTION_GESTURE = "GESTURE";

    public static final int DEFAULT_SERVER_PORT = 9443;
    public static final String DEFAULT_SERVER_PATH = "/rtp-web-admin/";
}
