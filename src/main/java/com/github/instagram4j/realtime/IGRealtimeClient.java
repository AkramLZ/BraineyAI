package com.github.instagram4j.realtime;

import com.github.instagram4j.instagram4j.IGClient;
import com.github.instagram4j.instagram4j.requests.direct.DirectInboxRequest;
import com.github.instagram4j.instagram4j.responses.direct.DirectInboxResponse;
import com.github.instagram4j.instagram4j.utils.IGUtils;
import com.github.instagram4j.realtime.mqtt.MQTToTClient;
import com.github.instagram4j.realtime.mqtt.packet.Packet;
import com.github.instagram4j.realtime.mqtt.packet.Payload;
import com.github.instagram4j.realtime.mqtt.packet.PingReqPacket;
import com.github.instagram4j.realtime.mqtt.packet.PublishPacket;
import com.github.instagram4j.realtime.payload.IGRealtimePayload;
import com.github.instagram4j.realtime.payload.IrisPayload;
import com.github.instagram4j.realtime.utils.ZipUtil;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import dev.akraml.brainey.Main;
import org.apache.thrift.TException;

public class IGRealtimeClient {
    private final IGClient igClient;
    private final MQTToTClient mqttClient;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private Future<?> pingTask;

    @SafeVarargs
    public IGRealtimeClient(IGClient igClient, Consumer<Packet>... packetListeners) {
        this.igClient = igClient;
        this.mqttClient = new MQTToTClient("edge-mqtt.facebook.com", 443, packetListeners);
    }

    public void connect() {
        this.connect(() -> {
        });
    }

    public void connect(Runnable onReady) {
        try {
            this.mqttClient.connect(ZipUtil.zip((new IGRealtimePayload(this.igClient)).toThriftPayload()), () -> {
                try {
                    this.sendPubIris();
                    this.startPingTask();
                    onReady.run();
                } catch (IOException var3) {
                    throw new UncheckedIOException(var3);
                }
            });
        } catch (IllegalAccessException | IOException | TException | IllegalArgumentException var3) {
            throw new RuntimeException(var3);
        }
    }

    public void disconnect() throws IOException {
        this.pingTask.cancel(true);
        this.mqttClient.disconnect();
        this.mqttClient.close();
    }

    private void startPingTask() {
        this.pingTask = this.scheduler.scheduleAtFixedRate(() -> {
            try {
                this.mqttClient.send(new PingReqPacket());
            } catch (IOException var2) {
                var2.printStackTrace(System.err);
                try {
                    disconnect();
                } catch (Exception exception) {
                    exception.printStackTrace(System.err);
                }
                new Timer().schedule(new TimerTask() {
                    @Override
                    public void run() {
                        try {
                            Main.reconnect();
                        } catch (Exception exception1) {
                            System.out.println("Failed to reconnect, trying again...");
                            if (Main.STACKTRACE) exception1.printStackTrace(System.err);
                        }
                    }
                }, Duration.ofSeconds(30).toMillis());
            }

        }, 19500L, 19500L, TimeUnit.MILLISECONDS);
    }

    private void sendPubIris() throws IOException {
        Payload payload = new Payload();
        String json = IGUtils.objectToJson(new IrisPayload((DirectInboxResponse)this.igClient.sendRequest(new DirectInboxRequest()).join()));
        payload.writeByteArray(json.getBytes());
        payload.compress();
        Packet iris = new PublishPacket(false, (byte)1, false, "134", (short)5, payload.toByteArray());
        this.mqttClient.send(iris);
    }
}