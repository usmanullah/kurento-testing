/*
 * (C) Copyright 2015 Kurento (http://kurento.org/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.kurento.tutorial.player;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

import org.kurento.client.*;
import org.kurento.commons.exception.KurentoException;
import org.kurento.jsonrpc.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * Protocol handler for video player through WebRTC.
 *
 * @author Boni Garcia (bgarcia@gsyc.es)
 * @author David Fernandez (dfernandezlop@gmail.com)
 * @author Ivan Gracia (igracia@kurento.org)
 * @since 6.1.1
 */
public class PlayerHandler extends TextWebSocketHandler {

    @Autowired
    private KurentoClient kurento;

    private final Logger log = LoggerFactory.getLogger(PlayerHandler.class);
    private final Gson gson = new GsonBuilder().create();
    private final ConcurrentHashMap<String, UserSession> users = new ConcurrentHashMap<>();


    private static final String offer = "v=0\r\no=- 0 0 IN IP4 0.0.0.0\r\ns=\r\nc=IN IP4 0.0.0.0\r\nt=0 0\r\n"
            + "m=audio 5005 RTP/AVPF 96\r\na=rtpmap:96 opus/48000/2\r\na=sendonly\r\n"
            + "m=video 5000 RTP/AVPF 103\r\na=rtpmap:103 H264/90000\r\na=sendonly";


    private RtpEndpoint rtpEndpoint;
    private WebRtcEndpoint webRtcEndpoint;


    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonObject jsonMessage = gson.fromJson(message.getPayload(), JsonObject.class);
        String sessionId = session.getId();
        log.debug("Incoming message {} from sessionId", jsonMessage, sessionId);

        try {
            switch (jsonMessage.get("id").getAsString()) {
                case "start":
                    start(session, jsonMessage);
                    break;
                case "stop":
                    stop(sessionId);
                    break;
                case "pause":
                    pause(sessionId);
                    break;
                case "resume":
                    resume(session);
                    break;
                case "doSeek":
                    doSeek(session, jsonMessage);
                    break;
                case "getPosition":
                    getPosition(session);
                    break;
                case "onIceCandidate":
                    onIceCandidate(sessionId, jsonMessage);
                    break;
                default:
                    sendError(session, "Invalid message with id " + jsonMessage.get("id").getAsString());
                    break;
            }
        } catch (Throwable t) {
            log.error("Exception handling message {} in sessionId {}", jsonMessage, sessionId, t);
            sendError(session, t.getMessage());
        }
    }

    private void start(final WebSocketSession session, JsonObject jsonMessage) {
        // 1. Media pipeline
        MediaPipeline pipeline = kurento.createMediaPipeline();
        webRtcEndpoint = new WebRtcEndpoint.Builder(pipeline).build();

        rtpEndpoint = new RtpEndpoint.Builder(pipeline).build();
        rtpEndpoint.connect(webRtcEndpoint);


//    String answer = rtpEndpoint.generateOffer();
        String answer = rtpEndpoint.processOffer(offer);
        System.out.println("answer to be used by ffmpeg = " + answer);

        new Thread(runner(rtpEndpoint, webRtcEndpoint)).start();

        webRtcEndpoint.addIceCandidateFoundListener(event -> {
            JsonObject response = new JsonObject();
            response.addProperty("id", "iceCandidate");
            response.add("candidate", JsonUtils.toJsonObject(event.getCandidate()));
            try {
                synchronized (session) {
                    session.sendMessage(new TextMessage(response.toString()));
                }
            } catch (IOException e) {
                log.debug(e.getMessage());
            }
        });

        String sdpOffer = jsonMessage.get("sdpOffer").getAsString();
        String sdpAnswer = webRtcEndpoint.processOffer(sdpOffer);

        System.out.println("sdpAnswer = " + sdpAnswer);

        JsonObject response = new JsonObject();
        response.addProperty("id", "startResponse");
        response.addProperty("sdpAnswer", sdpAnswer);
        sendMessage(session, response.toString());

        webRtcEndpoint.addMediaStateChangedListener(event -> {
            System.out.println("New State = " + event.getNewState().name());
            System.out.println("Old State = " + event.getOldState().name());
        });

        webRtcEndpoint.gatherCandidates();
    }

    private Runnable runner(final RtpEndpoint rtpEndpoint, final WebRtcEndpoint webRtcEndpoint) {
        return () -> {
            while (true) {
                try {
                    Thread.sleep(5000);
                    System.out.println("rtpEndpoint.isMediaFlowingIn(MediaType.VIDEO) = " + rtpEndpoint.isMediaFlowingIn(MediaType.VIDEO));
                    System.out.println("rtpEndpoint.isMediaFlowingOut(MediaType.VIDEO) = " + rtpEndpoint.isMediaFlowingOut(MediaType.VIDEO));

                    System.out.println("webRtcEndpoint.isMediaFlowingIn(MediaType.VIDEO) = " + webRtcEndpoint.isMediaFlowingIn(MediaType.VIDEO));
                    System.out.println("webRtcEndpoint.isMediaFlowingOut(MediaType.VIDEO) = " + webRtcEndpoint.isMediaFlowingOut(MediaType.VIDEO));
                } catch (InterruptedException e) {
                    System.out.println("excpetion = " + e);
                }
            }
        };
    }

    private void pause(String sessionId) {
        UserSession user = users.get(sessionId);

        if (user != null) {
            user.getPlayerEndpoint().pause();
        }
    }

    private void resume(final WebSocketSession session) {
        UserSession user = users.get(session.getId());

        if (user != null) {
            user.getPlayerEndpoint().play();
            VideoInfo videoInfo = user.getPlayerEndpoint().getVideoInfo();

            JsonObject response = new JsonObject();
            response.addProperty("id", "videoInfo");
            response.addProperty("isSeekable", videoInfo.getIsSeekable());
            response.addProperty("initSeekable", videoInfo.getSeekableInit());
            response.addProperty("endSeekable", videoInfo.getSeekableEnd());
            response.addProperty("videoDuration", videoInfo.getDuration());
            sendMessage(session, response.toString());
        }
    }

    private void stop(String sessionId) {
        UserSession user = users.remove(sessionId);

        if (user != null) {
            user.release();
        }
    }

    private void doSeek(final WebSocketSession session, JsonObject jsonMessage) {
        UserSession user = users.get(session.getId());

        if (user != null) {
            try {
                user.getPlayerEndpoint().setPosition(jsonMessage.get("position").getAsLong());
            } catch (KurentoException e) {
                log.debug("The seek cannot be performed");
                JsonObject response = new JsonObject();
                response.addProperty("id", "seek");
                response.addProperty("message", "Seek failed");
                sendMessage(session, response.toString());
            }
        }
    }

    private void getPosition(final WebSocketSession session) {
        UserSession user = users.get(session.getId());

        if (user != null) {
            long position = user.getPlayerEndpoint().getPosition();

            JsonObject response = new JsonObject();
            response.addProperty("id", "position");
            response.addProperty("position", position);
            sendMessage(session, response.toString());
        }
    }

    private void onIceCandidate(String sessionId, JsonObject jsonMessage) {
        UserSession user = users.get(sessionId);

        if (user != null) {
            JsonObject jsonCandidate = jsonMessage.get("candidate").getAsJsonObject();
            IceCandidate candidate =
                    new IceCandidate(jsonCandidate.get("candidate").getAsString(), jsonCandidate
                            .get("sdpMid").getAsString(), jsonCandidate.get("sdpMLineIndex").getAsInt());
            user.getWebRtcEndpoint().addIceCandidate(candidate);
        }
    }

    public void sendPlayEnd(WebSocketSession session) {
        if (users.containsKey(session.getId())) {
            JsonObject response = new JsonObject();
            response.addProperty("id", "playEnd");
            sendMessage(session, response.toString());
        }
    }

    private void sendError(WebSocketSession session, String message) {
        if (users.containsKey(session.getId())) {
            JsonObject response = new JsonObject();
            response.addProperty("id", "error");
            response.addProperty("message", message);
            sendMessage(session, response.toString());
        }
    }

    private synchronized void sendMessage(WebSocketSession session, String message) {
        try {
            session.sendMessage(new TextMessage(message));
        } catch (IOException e) {
            log.error("Exception sending message", e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        stop(session.getId());
    }
}
