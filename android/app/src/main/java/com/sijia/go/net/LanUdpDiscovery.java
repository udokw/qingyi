package com.sijia.go.net;

import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * UDP 房间发现（mDNS 的并行通道）。
 *
 * <p>客机每 1.5 秒向 255.255.255.255:41235 广播 {@code room_query}（空房号 = 查全部），
 * 房主的 {@link LanRoomQuery.Responder} 直接应答；房主另外每 3 秒广播一次
 * {@code room_announce}。应答/通告的源地址就是房主在本网段的真实 IPv4，
 * 与「手动填 IP 能连」走同一条通路，因此这条通道能通就说明坏的是 mDNS 那一层。
 */
public final class LanUdpDiscovery implements Runnable {
    private static final String TAG = "LanUdpDiscovery";
    private static final long QUERY_INTERVAL_MS = 1500;
    private static final long EXPIRE_MS = 8000;

    public interface Listener {
        void onFound(LanDiscovery.Service service);
        void onLost(String serviceName);
    }

    private final Listener listener;
    private final String gameFilter;
    private final Map<String, Long> seen = new HashMap<>();
    private final Map<String, String> names = new HashMap<>();
    private volatile boolean running;
    private volatile DatagramSocket socket;
    private Thread thread;

    public LanUdpDiscovery(Listener listener, String gameFilter) {
        this.listener = listener;
        this.gameFilter = gameFilter;
    }

    public void start() {
        if (thread != null) return;
        running = true;
        thread = new Thread(this, "lan-udp-discovery");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        DatagramSocket s = socket;
        if (s != null) s.close();
        thread = null;
    }

    @Override
    public void run() {
        DatagramSocket s = null;
        try {
            // 先记录本机 IPv4：诊断区第一行要靠它区分「本机回环」和「外部来源」
            LanDiag.get().localIp(LanRoomQuery.localIpv4());
            s = new DatagramSocket(null);
            s.setReuseAddress(true);
            s.setBroadcast(true);
            try {
                s.bind(new InetSocketAddress(LanRoomQuery.QUERY_PORT));
                Log.i(TAG, "已绑定 " + LanRoomQuery.QUERY_PORT + "，可同时收 room_announce");
                LanDiag.get().udpBind(true, s.getLocalPort(), "绑定 41235，可收通告");
            } catch (Exception e) {
                Log.w(TAG, "bind " + LanRoomQuery.QUERY_PORT + " 失败（本机可能在当房主）："
                        + e.getMessage() + "，改用临时端口，仅走查询应答");
                LanDiag.get().drop("bind", "41235 绑定失败（" + e.getMessage() + "），改用临时端口");
                s.close();
                s = new DatagramSocket();
                s.setBroadcast(true);
                LanDiag.get().udpBind(true, s.getLocalPort(), "临时端口，收不到广播通告");
            }
            s.setSoTimeout(250);
            socket = s;
            Log.i(TAG, "UDP 房间发现已启动，广播目标 " + LanRoomQuery.broadcastAddresses());

            byte[] buf = new byte[2048];
            long lastQuery = 0;
            while (running) {
                long now = System.currentTimeMillis();
                if (now - lastQuery >= QUERY_INTERVAL_MS) {
                    lastQuery = now;
                    sendQuery(s);
                }
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(packet);
                } catch (IOException e) {
                    expire();
                    continue;
                }
                handle(packet);
                expire();
            }
        } catch (Throwable t) {
            Log.e(TAG, "UDP 房间发现异常退出", t);
            LanDiag.get().udpBind(false, 0, "启动失败：" + t.getMessage());
            LanDiag.get().drop("udp", "发现线程异常退出：" + t.getMessage());
        } finally {
            if (s != null) s.close();
            socket = null;
        }
    }

    private void sendQuery(DatagramSocket s) {
        int sent = 0;
        try {
            JSONObject query = new JSONObject();
            query.put("t", Proto.UDP_QUERY);
            query.put("room", "");
            query.put("v", Proto.VERSION);
            byte[] out = query.toString().getBytes("UTF-8");
            for (InetAddress target : LanRoomQuery.broadcastAddresses()) {
                try {
                    s.send(new DatagramPacket(out, out.length, target, LanRoomQuery.QUERY_PORT));
                    sent++;
                } catch (IOException e) {
                    LanDiag.get().drop(target.getHostAddress(), "发送 room_query 失败：" + e.getMessage());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "发送广播查询失败：" + e.getMessage());
            LanDiag.get().drop("query", "构造 room_query 失败：" + e.getMessage());
        }
        if (sent == 0) {
            LanDiag.get().drop("query", "没有任何可用的广播地址，查询未发出");
        }
        LanDiag.get().querySent(sent);
    }

    private void handle(DatagramPacket packet) {
        String srcIp = packet.getAddress() != null ? packet.getAddress().getHostAddress() : "";
        String src = (srcIp.isEmpty() ? "?" : srcIp) + ":" + packet.getPort();
        // 广播会回环到本机 socket：必须标出来，否则无法判断 .54 是对方还是自己
        boolean self = LanRoomQuery.isLocalAddress(srcIp);
        String srcTag = src + (self ? " [本机回环]" : " [外部]");
        String snippet = "";
        try {
            String text = new String(packet.getData(), 0, packet.getLength(), "UTF-8");
            snippet = text.length() > 200 ? text.substring(0, 200) + "…" : text;
            // 先无条件把收到的原文打出来：这样才能区分「没收到」和「收到但被丢弃」
            Log.i(TAG, "收到 UDP 包 src=" + srcTag + " len=" + packet.getLength() + " body=" + snippet);
            JSONObject obj = new JSONObject(text);
            String rawType = Proto.str(obj, "t", "");
            String type = Proto.udpKind(rawType);
            String code = normalize(Proto.str(obj, "room", ""));
            String game = Proto.str(obj, "game", "");
            String iid = Proto.str(obj, "iid", "");
            int port = Proto.num(obj, "port", 0);
            LanDiag.get().packetReceived(type.isEmpty() ? "(空)" : type, srcTag, code, game, iid,
                    snippet, packet.getLength());

            if (Proto.isUdpQuery(type)) {
                // 查询包不是候选房间：自己发的计入回环，别人的只证明「广播到得了本机」
                if (self) LanDiag.get().selfEcho(srcTag);
                else LanDiag.get().queryReceived(srcTag);
                return;
            }
            if (!Proto.isUdpAnswer(type)) {
                if (code.isEmpty() || port <= 0 || port > 65535) {
                    String reason = "类型未识别 t=" + (rawType.isEmpty() ? "(空)" : rawType)
                            + " 原文=" + snippet;
                    Log.w(TAG, "丢弃：" + reason);
                    LanDiag.get().drop(srcTag, reason);
                    return;
                }
                // 类型名不标准但字段齐全：按房间通告兼容处理，避免两端命名微差让整条通道失效
                LanDiag.get().compat(srcTag, rawType, snippet);
            }

            if (code.isEmpty() || port <= 0 || port > 65535) {
                String reason = "房号/端口非法 room=" + code + " port=" + port
                        + "（类型=" + rawType + " 原文=" + snippet + "）";
                Log.w(TAG, "丢弃：" + reason);
                LanDiag.get().drop(srcTag, reason);
                return;
            }

            // 优先用数据包源地址：这正是「手动填 IP」用的那个地址
            String host = srcIp;
            if (host.isEmpty()) host = Proto.str(obj, "addr", "");
            if (host.isEmpty()) {
                String reason = "取不到源地址且报文中没有 addr room=" + code
                        + "（类型=" + rawType + " 原文=" + snippet + "）";
                Log.w(TAG, "丢弃：" + reason);
                LanDiag.get().drop(srcTag, reason);
                return;
            }

            String gameIn = game.isEmpty() ? GameAdapter.GO : game;
            if (gameFilter != null && !gameFilter.isEmpty() && !gameFilter.equals(gameIn)) {
                String reason = "棋种不匹配 game=" + gameIn + " 需要 " + gameFilter
                        + "（类型=" + rawType + " 原文=" + snippet + "）";
                Log.w(TAG, "丢弃：" + reason);
                LanDiag.get().drop(srcTag, reason);
                return;
            }
            String size = Proto.str(obj, "size", "");
            if (size.isEmpty()) size = GameAdapter.XIANGQI.equals(gameIn) ? "9" : "19";

            String key = iid.isEmpty() ? code + "@" + host + ":" + port : iid;
            Long last = seen.get(key);
            seen.put(key, System.currentTimeMillis());
            LanDiscovery.Service service =
                    LanDiscovery.Service.fromUdp(code, size, host, port, gameIn, iid);
            names.put(key, service.name);
            if (last == null) {
                Log.i(TAG, "UDP 发现房间 " + code + " " + gameIn + " " + host + ":" + port
                        + " via " + (rawType.isEmpty() ? "(无类型)" : rawType));
                LanDiag.get().found(code, host + ":" + port);
            }
            listener.onFound(service);
        } catch (Throwable t) {
            Log.w(TAG, "解析 UDP 包失败：" + t.getMessage());
            LanDiag.get().drop(srcTag, "解析失败：" + t.getMessage() + " 原文=" + snippet);
        }
    }

    private void expire() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            if (now - e.getValue() <= EXPIRE_MS) continue;
            it.remove();
            String key = e.getKey();
            String name = names.remove(key);
            if (name == null) continue;
            Log.i(TAG, "UDP 房间超时移除 " + name);
            listener.onLost(name);
        }
    }

    private static String normalize(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(4);
        for (int i = 0; i < text.length() && sb.length() < 4; i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') sb.append(c);
        }
        return sb.toString();
    }
}
