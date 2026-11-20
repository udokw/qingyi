package com.sijia.go.net;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 本机回环自检：Host + Guest 走 127.0.0.1，验证 FrameIO 分帧、hello/hello_ack 握手、
 * 中文与数组的 UTF-8 编码。用于把“协议问题”和“局域网/mDNS 发现问题”区分开。
 */
public final class LanSelfTest {
    private static final String TAG = "LanSelfTest";

    public interface Callback {
        void onResult(boolean ok, String log);
    }

    private LanSelfTest() {}

    public static void run(Callback cb) {
        new Thread(() -> {
            StringBuilder log = new StringBuilder();
            LanHost host = new LanHost();
            LanClient client = new LanClient();
            boolean ok = false;
            try {
                final CountDownLatch ackLatch = new CountDownLatch(1);
                final CountDownLatch snapLatch = new CountDownLatch(1);
                final String[] hostName = new String[]{""};
                final String[] note = new String[]{""};

                LanHost.Callback hostCb = new LanHost.Callback() {
                    @Override
                    public void onMessage(JSONObject msg) {
                        String t = Proto.type(msg);
                        log.append("host<- ").append(t).append('\n');
                        if (!Proto.HELLO.equals(t)) return;
                        try {
                            JSONObject ack = new JSONObject();
                            ack.put("ok", true);
                            ack.put("yourColor", "W");
                            ack.put("opponentName", "主机测试");
                            host.send(Proto.msg(Proto.HELLO_ACK, "TEST", "host", ack));

                            JSONObject snap = new JSONObject();
                            snap.put("note", "中文快照·十九路");
                            snap.put("arr", new JSONArray().put(1).put(2).put(3));
                            host.send(Proto.msg(Proto.SNAPSHOT, "TEST", "host", snap));
                        } catch (Exception e) {
                            log.append("host 回包异常：").append(e).append('\n');
                        }
                    }

                    @Override
                    public void onGuestChanged(boolean connected) {
                        log.append("host guestChanged=").append(connected).append('\n');
                    }

                    @Override
                    public void onError(String message) {
                        log.append("host error：").append(message).append('\n');
                    }
                };

                int port = host.start(45321, 20, hostCb);
                log.append("host listen 127.0.0.1:").append(port).append('\n');

                client.connect("127.0.0.1", port, new LanClient.Callback() {
                    @Override
                    public void onMessage(JSONObject msg) {
                        String t = Proto.type(msg);
                        log.append("guest<- ").append(t).append('\n');
                        if (Proto.HELLO_ACK.equals(t)) {
                            hostName[0] = Proto.p(msg).optString("opponentName", "");
                            ackLatch.countDown();
                        } else if (Proto.SNAPSHOT.equals(t)) {
                            note[0] = Proto.p(msg).optString("note", "");
                            snapLatch.countDown();
                        }
                    }

                    @Override
                    public void onClosed(String reason) {
                        log.append("guest closed：").append(reason).append('\n');
                        ackLatch.countDown();
                        snapLatch.countDown();
                    }
                });

                JSONObject hello = new JSONObject();
                hello.put("protocolVersion", Proto.VERSION);
                hello.put("roomCode", "TEST");
                hello.put("deviceName", "客机测试·中文");
                boolean sent = client.send(Proto.msg(Proto.HELLO, "TEST", "guest", hello));
                log.append("guest-> hello sent=").append(sent).append('\n');

                boolean gotAck = ackLatch.await(5, TimeUnit.SECONDS);
                boolean gotSnap = gotAck && snapLatch.await(5, TimeUnit.SECONDS);
                boolean textOk = "主机测试".equals(hostName[0]) && "中文快照·十九路".equals(note[0]);
                ok = sent && gotAck && gotSnap && textOk;
                log.append("ack=").append(gotAck)
                        .append(" snapshot=").append(gotSnap)
                        .append(" 名称=").append(hostName[0])
                        .append(" 中文=").append(note[0]).append('\n');
            } catch (Exception e) {
                Log.e(TAG, "自检异常", e);
                log.append("异常：").append(e).append('\n');
            } finally {
                client.close();
                host.stop();
            }
            final boolean result = ok;
            final String text = log.toString();
            Log.i(TAG, (result ? "自检通过\n" : "自检失败\n") + text);
            if (cb != null) cb.onResult(result, text);
        }, "lan-selftest").start();
    }
}
