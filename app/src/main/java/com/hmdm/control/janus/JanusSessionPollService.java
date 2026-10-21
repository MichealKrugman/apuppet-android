package com.hmdm.control.janus;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

import com.hmdm.control.Const;
import com.hmdm.control.ServerApiHelper;
import com.hmdm.control.janus.json.JanusPollResponse;
import com.hmdm.control.janus.server.JanusServerApi;
import com.hmdm.control.janus.server.JanusServerApiFactory;

import retrofit2.Call;
import retrofit2.Response;

public class JanusSessionPollService extends Service {
    // A healthy long-poll returns a Janus keepalive roughly every ~30s (server
    // keepalive). A couple of consecutive transport failures therefore means the
    // session is genuinely gone (network dropped, or the server timed it out),
    // not a transient blip. We surface that so the supervisor can reconnect
    // instead of hammering a dead session id forever.
    private static final int MAX_CONSECUTIVE_POLL_FAILURES = 3;

    private int state = Const.STATE_DISCONNECTED;
    private String session;

    private JanusServerApi apiInstance;
    private String secret;
    Call<JanusPollResponse> currentCall;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            // Exit the app, let's just finish
            return Service.START_NOT_STICKY;
        }
        String session = intent.getStringExtra(Const.EXTRA_SESSION);
        if (session != null) {
            this.session = session;
        }

        if (state == Const.STATE_DISCONNECTED) {
            apiInstance = JanusServerApiFactory.getApiInstance(this);
            secret = JanusServerApiFactory.getSecret(this);

            // Start polling
            state = Const.STATE_CONNECTED;
            loop();
        }
        return Service.START_STICKY;
    }

    public void loop() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                int consecutiveFailures = 0;
                while (true) {
                    currentCall = apiInstance.poll(session, secret);
                    Response<JanusPollResponse> response = ServerApiHelper.execute(currentCall, "poll session");
                    if (response == null) {
                        if (state == Const.STATE_DISCONNECTING) {
                            // Service stopped
                            state = Const.STATE_DISCONNECTED;
                            break;
                        } else {
                            consecutiveFailures++;
                            if (consecutiveFailures >= MAX_CONSECUTIVE_POLL_FAILURES) {
                                // The session is dead. Tell the supervisor so it
                                // tears down and reconnects, then stop this loop.
                                Log.w(Const.LOG_TAG, "Poll failed " + consecutiveFailures
                                        + " times in a row; declaring session lost");
                                broadcastSessionLost();
                                state = Const.STATE_DISCONNECTED;
                                break;
                            }
                            try {
                                Thread.sleep(1000);         // Avoid looping with 100% CPU
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                            continue;
                        }
                    }
                    consecutiveFailures = 0;
                    Log.d(Const.LOG_TAG, "Polled data: " + response.body().toString());
                    String janus = response.body().getJanus();
                    if (janus == null) {
                        // Error!
                        Log.w(Const.LOG_TAG, "Wrong response body: " + response.body().toString());
                    } else if (janus.equalsIgnoreCase("keepalive")) {
                        Log.d(Const.LOG_TAG, "Janus: Keep-Alive");
                    } else if (janus.equalsIgnoreCase("webrtcup")) {
                        Log.i(Const.LOG_TAG, "Poll: sending webrtcup broadcast");
                        Intent intent = new Intent(Const.ACTION_JANUS_SESSION_POLL);
                        // Explicitly target our own package: an implicit broadcast is
                        // not reliably delivered to a RECEIVER_NOT_EXPORTED receiver.
                        intent.setPackage(getPackageName());
                        intent.putExtra(Const.EXTRA_EVENT, Const.EXTRA_WEBRTCUP);
                        sendBroadcast(intent);
                    } else if (janus.equalsIgnoreCase("event")) {
                        Log.i(Const.LOG_TAG, "Poll: sending event broadcast, plugindata=" + (response.body().getPlugindata() == null ? "NULL" : response.body().getPlugindata().getPlugin()));
                        Intent intent = new Intent(Const.ACTION_JANUS_SESSION_POLL);
                        // Explicitly target our own package: an implicit broadcast is
                        // not reliably delivered to a RECEIVER_NOT_EXPORTED receiver.
                        intent.setPackage(getPackageName());
                        intent.putExtra(Const.EXTRA_EVENT, Const.EXTRA_EVENT);
                        intent.putExtra(Const.EXTRA_MESSAGE, response.body());
                        sendBroadcast(intent);
                    } else {
                        Log.w(Const.LOG_TAG, "Unknown poll result: " + response.body().toString());
                    }
                }
            }
        }).start();
    }

    private void broadcastSessionLost() {
        // Explicitly target our own package: an implicit broadcast is not
        // reliably delivered to a RECEIVER_NOT_EXPORTED receiver.
        Intent intent = new Intent(Const.ACTION_CONNECTION_LOST);
        intent.setPackage(getPackageName());
        intent.putExtra(Const.EXTRA_SESSION, session);
        sendBroadcast(intent);
    }

    @Override
    public void onDestroy() {
        state = Const.STATE_DISCONNECTING;
        currentCall.cancel();
    }
}
