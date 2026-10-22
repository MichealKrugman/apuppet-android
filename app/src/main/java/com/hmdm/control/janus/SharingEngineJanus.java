package com.hmdm.control.janus;

import android.content.Context;
import android.os.AsyncTask;
import android.os.Handler;
import android.util.Log;

import com.hmdm.control.Const;
import com.hmdm.control.SharingEngine;
import com.hmdm.control.janus.server.JanusServerApi;
import com.hmdm.control.janus.server.JanusServerApiFactory;

public class SharingEngineJanus extends SharingEngine {
    private JanusServerApi apiInstance;

    private JanusSession janusSession;
    private JanusTextRoomPlugin janusTextRoomPlugin;
    private JanusStreamingPlugin janusStreamingPlugin;

    // Guards connect() against overlapping connect flows: a burst of
    // connection-lost signals (flappy NAT) used to start a second connect()
    // whose reset() nulled janusSession while the first flow's AsyncTask was
    // still using it (NPE, process death). Cleared when the flow completes or
    // on disconnect(), and released by the 90s guard timeout below.
    private volatile boolean connectInFlight;
    // Monotonic id: a guard-timeout scheduled by an old flow must not release
    // a newer flow's guard.
    private volatile int connectGeneration;

    private Handler handler = new Handler();

    @Override
    public void connect(final Context context, final String sessionId, final String password, final CompletionHandler completionHandler) {
        try {
            JanusServerApiFactory.resetApiInstance();
            apiInstance = JanusServerApiFactory.getApiInstance(context);
        } catch (Exception e) {
            e.printStackTrace();
            completionHandler.onComplete(false, "Wrong server URL");
            return;
        }

        if (state != Const.STATE_DISCONNECTED) {
            completionHandler.onComplete(false, "Not disconnected");
            return;
        }

        if (connectInFlight) {
            Log.d(Const.LOG_TAG, "connect already in progress, ignoring");
            completionHandler.onComplete(false, "Connect already in progress");
            return;
        }

        reset();
        this.sessionId = sessionId;
        this.password = password;
        setState(Const.STATE_CONNECTING);

        janusSession = new JanusSession();
        janusSession.init(context);

        // This must be initialized in the main thread because it uses a handler to run commands in UI thread
        janusTextRoomPlugin = new JanusTextRoomPlugin();

        // Final captures: the tasks below run across suspension points while
        // reset()/disconnect() may null the fields; always use these locals.
        final JanusSession session = janusSession;
        final JanusTextRoomPlugin textRoom = janusTextRoomPlugin;
        final CompletionHandler wrapped = new CompletionHandler() {
            @Override
            public void onComplete(boolean success, String errorReason) {
                // A superseded flow (reset() ran, a newer connect owns the
                // field) must be fully inert: no state changes, no callback
                // into the supervisor, no unlocking the newer flow's guard.
                if (janusSession != session) {
                    Log.d(Const.LOG_TAG, "stale connect flow result ignored: success=" + success);
                    return;
                }
                connectInFlight = false;
                completionHandler.onComplete(success, errorReason);
            }
        };
        connectInFlight = true;
        // Belt-and-braces: if this flow wedges (e.g. a wss created on a
        // network that then vanished, so no completion callback ever fires),
        // release the guard after 90s so the supervisor's scheduled reconnect
        // can proceed. Observed live 2026-10-22: cellular died mid-connect and
        // every later reconnect was rejected with "Connect already in progress".
        final int generation = ++connectGeneration;
        handler.postDelayed(() -> {
            if (connectInFlight && connectGeneration == generation) {
                Log.w(Const.LOG_TAG, "connect guard timeout after 90s, releasing");
                connectInFlight = false;
            }
        }, 90000);

        // Start Janus connection flow
        new AsyncTask<Void, Void, Integer>() {
            @Override
            protected Integer doInBackground(Void... voids) {
                int result = session.create();
                if (result != Const.SUCCESS) {
                    errorReason = session.getErrorReason();
                    return result;
                }

                session.startPolling(context);

                textRoom.init(context);
                result = session.attachPlugin(textRoom);
                if (result != Const.SUCCESS) {
                    return result;
                }

                // The successful flow is continued after creating a data channel
                textRoom.createPeerConnection((success, errorReason) -> {
                            if (!success) {
                                handler.post(() -> wrapped.onComplete(false, errorReason));
                            } else {
                                // Pass this flow's objects explicitly: this
                                // callback runs on a WebRTC thread and reading
                                // the fields here raced a concurrent reset()
                                // (null plugin -> NPE in createRoom).
                                dataChannelCreated(context, wrapped, textRoom, session);
                            }
                        }, new EventListener() {
                            @Override
                            public void onStartSharing(String username) {
                                // Send screen resolution before starting sharing
                                janusTextRoomPlugin.sendMessage(screenResolutionMessage(), false);
                                if (eventListener != null) {
                                    eventListener.onStartSharing(username);
                                }
                            }

                            @Override
                            public void onStopSharing() {
                                if (eventListener != null) {
                                    eventListener.onStopSharing();
                                }
                            }

                            @Override
                            public void onPing() {
                                if (eventListener != null) {
                                    eventListener.onPing();
                                }
                            }

                            @Override
                            public void onRemoteControlEvent(String event) {
                                if (eventListener != null) {
                                    eventListener.onRemoteControlEvent(event);
                                }
                            }
                        });

                // Completion handler is needed here to handle errors
                textRoom.setupRtcSession((success, errorReason) -> {
                    handler.post(() -> wrapped.onComplete(false, errorReason));
                });

                return Const.SUCCESS;
            }

            @Override
            protected void onPostExecute(Integer result) {
                if (janusSession != session) {
                    // Superseded by a newer connect/disconnect: a stale failure
                    // must not tear down the current flow's state.
                    Log.d(Const.LOG_TAG, "stale connect flow result ignored: " + result);
                    return;
                }
                if (result != Const.SUCCESS) {
                    setState(Const.STATE_DISCONNECTED);
                    wrapped.onComplete(false, errorReason);
                    reset();
                }
                // On success, the flow is continued in createPeerConnection when data channel is created
            }
        }.execute();
    }

    private void dataChannelCreated(Context context, CompletionHandler completionHandler,
                                    final JanusTextRoomPlugin textRoom, final JanusSession session) {
        new AsyncTask<Void, Void, Integer>() {

            @Override
            protected Integer doInBackground(Void... voids) {
                int result = textRoom.createRoom(sessionId, password);
                if (result != Const.SUCCESS) {
                    return result;
                }

                result = textRoom.joinRoom("device:" + username, username);
                if (result != Const.SUCCESS) {
                    return result;
                }

                // A superseded flow must not install its streaming plugin over
                // the current flow's.
                if (janusTextRoomPlugin != textRoom) {
                    Log.d(Const.LOG_TAG, "stale datachannel flow, dropping");
                    return Const.SUCCESS;
                }

                // Streaming
                janusStreamingPlugin = new JanusStreamingPlugin();
                janusStreamingPlugin.init(context);
                result = session.attachPlugin(janusStreamingPlugin);
                if (result != Const.SUCCESS) {
                    return result;
                }

                result = janusStreamingPlugin.create(sessionId, password, isAudio());

                return result;
            }

            @Override
            protected void onPostExecute(Integer result) {
                if (janusTextRoomPlugin != textRoom) {
                    Log.d(Const.LOG_TAG, "stale datachannel flow result ignored: " + result);
                    return;
                }
                if (result != Const.SUCCESS) {
                    setState(Const.STATE_DISCONNECTED);
                    completionHandler.onComplete(false, errorReason);
                    reset();
                } else {
                    setState(Const.STATE_CONNECTED);
                    completionHandler.onComplete(true, null);
                }
            }
        }.execute();
    }

    @Override
    public void disconnect(final Context context, final CompletionHandler completionHandler) {
        // A disconnect supersedes any in-flight connect flow.
        connectInFlight = false;
        // Mark the current plugin stale NOW: janusSession.destroy() below
        // closes the PeerConnection, and its FAILED can fire between destroy
        // and the reset() in onPostExecute. A planned teardown must never
        // broadcast connection loss back into the supervisor.
        if (janusTextRoomPlugin != null) {
            janusTextRoomPlugin.markStale();
        }
        errorReason = null;
        setState(Const.STATE_DISCONNECTING);

        new AsyncTask<Void, Void, Integer>() {
            @Override
            protected Integer doInBackground(Void... voids) {
                // Registered plugins are destroyed in janusSession.destroy()
                if (janusSession != null) {
                    janusSession.destroy();
                }
                return Const.SUCCESS;
            }

            @Override
            protected void onPostExecute(Integer result) {
                if (janusSession != null) {
                    janusSession.stopPolling(context);
                }
                // Not really fair, but it's unclear how to handle destroying errors!
                setState(Const.STATE_DISCONNECTED);
                reset();
                completionHandler.onComplete(result == Const.SUCCESS, errorReason);
            }
        }.execute();

    }

    private void reset() {
        // The plugin we are about to drop belongs to a dead flow; mark it so
        // its PeerConnection's late FAILED cannot broadcast connection-loss
        // (that broadcast is what tore down healthy connections right after
        // they joined, sustaining the reconnect churn).
        if (janusTextRoomPlugin != null) {
            janusTextRoomPlugin.markStale();
        }
        sessionId = null;
        password = null;
        janusSession = null;
        janusStreamingPlugin = null;
        janusTextRoomPlugin = null;
    }

    @Override
    public int getAudioPort() {
        if (janusStreamingPlugin != null) {
            return janusStreamingPlugin.getAudioPort();
        }
        return 0;
    }

    @Override
    public int getVideoPort() {
        if (janusStreamingPlugin != null) {
            return janusStreamingPlugin.getVideoPort();
        }
        return 0;
    }

    private String screenResolutionMessage() {
        return "streamingVideoResolution," + screenWidth + "," + screenHeight;
    }
}
