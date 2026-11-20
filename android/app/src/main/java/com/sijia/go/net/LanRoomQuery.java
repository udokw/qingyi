package com.sijia.go.net;

import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 「只填房号」加入时的兜底发现：UDP 广播按房号定向查询。
 * mDNS 被路由器/热点过滤时通常还能用；应答里带真实 TCP 端口，
 * 因此客机不需要假设端口落在某个区间。
 */
public final class LanRoomQuery {
    private static final String TAG = "LanRoomQuery";
    public static final int QUERY_PORT = 41235;

    private LanRoomQuery() {}

    /** 查询结果：除了地址，还带回「发了几次 / 收到几个应答 / 为什么丢弃」。 */
    public static final class Lookup {
        public final List<String> endpoints = new ArrayList<>();
        public String target = "";
        public int sent;
        public int replies;
        public int dropped;
        public String lastDrop = "";
        public boolean socketOk = true;
    }

    /** 广播查询房号，返回 ["192.168.1.5:54321", ...]。 */
    public static List<String> lookup(String code, int timeoutMs) {
        return lookup(null, code, timeoutMs);
    }

    /**
     * 按房号查找房间。host 为空时走广播；指定 host（裸 IP）时只向该地址查询，
     * 用于用户手动填了 IP 但没填端口的情况——应答里带真实端口，不必猜端口区间。
     */
    public static List<String> lookup(String host, String code, int timeoutMs) {
        return query(host, code, timeoutMs).endpoints;
    }

    /** 同 {@link #lookup(String, String, int)}，但返回带诊断计数的结果。 */
    public static Lookup query(String host, String code, int timeoutMs) {
        Lookup out = new Lookup();
        if (code == null || code.isEmpty()) return out;
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setBroadcast(true);
            socket.setSoTimeout(120);
            JSONObject payload = new JSONObject();
            payload.put("t", Proto.UDP_QUERY);
            payload.put("room", code);
            payload.put("v", Proto.VERSION);
            byte[] bytes = payload.toString().getBytes("UTF-8");
            List<InetAddress> targets = new ArrayList<>();
            if (host != null && !host.trim().isEmpty()) {
                try {
                    targets.add(InetAddress.getByName(host.trim()));
                    out.target = host.trim();
                } catch (Exception e) {
                    Log.w(TAG, "房主地址无效: " + host);
                    LanDiag.get().drop(host, "手动地址无效：" + host);
                }
            }
            if (targets.isEmpty()) {
                targets.addAll(broadcastAddresses());
                StringBuilder sb = new StringBuilder();
                for (InetAddress a : targets) {
                    if (sb.length() > 0) sb.append(",");
                    sb.append(a.getHostAddress());
                }
                out.target = sb.length() == 0 ? "无广播地址" : sb.toString();
            }
            byte[] buf = new byte[2048];
            long deadline = System.currentTimeMillis() + timeoutMs;
            long lastSend = 0;
            while (System.currentTimeMillis() < deadline) {
                // 广播 UDP 可能被丢弃：每 400ms 重发一次
                if (System.currentTimeMillis() - lastSend > 400) {
                    for (InetAddress addr : targets) {
                        try {
                            socket.send(new DatagramPacket(bytes, bytes.length, addr, QUERY_PORT));
                            out.sent++;
                        } catch (IOException e) {
                            out.dropped++;
                            out.lastDrop = "发送失败：" + e.getMessage();
                            LanDiag.get().drop(addr.getHostAddress(), "发送 room_query 失败：" + e.getMessage());
                        }
                    }
                    lastSend = System.currentTimeMillis();
                }
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(packet);
                } catch (IOException e) {
                    continue;
                }
                String srcIp = packet.getAddress() == null ? "" : packet.getAddress().getHostAddress();
                String src = (srcIp.isEmpty() ? "?" : srcIp) + ":" + packet.getPort();
                boolean self = isLocalAddress(srcIp);
                String srcTag = src + (self ? " [本机回环]" : " [外部]");
                try {
                    String text = new String(packet.getData(), 0, packet.getLength(), "UTF-8");
                    String snippet = text.length() > 160 ? text.substring(0, 160) + "…" : text;
                    JSONObject reply = new JSONObject(text);
                    String rawType = Proto.str(reply, "t", "");
                    String type = Proto.udpKind(rawType);
                    String roomIn = normalize(Proto.str(reply, "room", ""));
                    String iid = Proto.str(reply, "iid", "");
                    int port = Proto.num(reply, "port", 0);
                    LanDiag.get().packetReceived(type.isEmpty() ? "(空)" : type, srcTag, roomIn,
                            Proto.str(reply, "game", ""), iid, snippet, packet.getLength());
                    if (Proto.isUdpQuery(type)) {
                        // 别的设备也在找房主：不是应答包，单独计数，不计入丢弃
                        LanDiag.get().queryReceived(srcTag);
                        continue;
                    }
                    if (!Proto.isUdpAnswer(type)) {
                        if (roomIn.isEmpty() || port <= 0 || port > 65535) {
                            out.dropped++;
                            out.lastDrop = "类型=" + (rawType.isEmpty() ? "(空)" : rawType)
                                    + " 不是应答/通告，且缺少 room+port；原文=" + snippet;
                            LanDiag.get().drop(srcTag, out.lastDrop);
                            continue;
                        }
                        // 类型名不标准但字段齐全：按应答兼容处理，避免命名微差让通道整体失效
                        LanDiag.get().compat(srcTag, rawType, snippet);
                    }
                    out.replies++;
                    if (!normalize(code).equals(roomIn)) {
                        out.dropped++;
                        out.lastDrop = "房号不匹配 room=" + roomIn + " 需要 " + normalize(code)
                                + "（类型=" + rawType + "）";
                        LanDiag.get().drop(srcTag, out.lastDrop);
                        continue;
                    }
                    // 应答包的源地址就是房主真实地址，比 TXT/字段里的 addr 更可靠
                    String addr = srcIp.isEmpty() ? Proto.str(reply, "addr", "") : srcIp;
                    if (addr.isEmpty() || port <= 0 || port > 65535) {
                        out.dropped++;
                        out.lastDrop = "应答地址/端口非法 addr=" + addr + " port=" + port
                                + "（类型=" + rawType + " 原文=" + snippet + "）";
                        LanDiag.get().drop(srcTag, out.lastDrop);
                        continue;
                    }
                    String endpoint = addr + ":" + port;
                    if (!out.endpoints.contains(endpoint)) out.endpoints.add(endpoint);
                    LanDiag.get().found(normalize(code), endpoint);
                } catch (Exception e) {
                    out.dropped++;
                    out.lastDrop = "解析失败：" + e.getMessage();
                    LanDiag.get().drop(srcTag, out.lastDrop);
                }
            }
        } catch (Exception e) {
            out.socketOk = false;
            Log.w(TAG, "房号查询失败: " + e.getMessage());
            LanDiag.get().drop("query", "UDP socket 不可用：" + e.getMessage());
        } finally {
            if (socket != null) socket.close();
        }
        LanDiag.get().note("按房号查询完成 room=" + code + " target=" + out.target
                + " 发出=" + out.sent + " 应答=" + out.replies
                + " 丢弃=" + out.dropped + " 命中=" + out.endpoints.size());
        return out;
    }

    /** 本机所有可用的广播地址（含 255.255.255.255 与各网卡定向广播）。 */
    public static List<InetAddress> broadcastAddresses() {
        List<InetAddress> out = new ArrayList<>();
        try {
            out.add(InetAddress.getByName("255.255.255.255"));
        } catch (Exception ignored) {
        }
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress broadcast = ia.getBroadcast();
                    if (broadcast != null && !out.contains(broadcast)) out.add(broadcast);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 本机当前用于局域网的 IPv4：优先 Wi-Fi 网卡，跳过回环与虚拟网卡。 */
    public static String localIpv4() {
        String fallback = "";
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName() == null ? "" : ni.getName();
                if (name.startsWith("rmnet") || name.startsWith("dummy")
                        || name.startsWith("tun") || name.startsWith("utun")
                        || name.startsWith("p2p")) {
                    continue;
                }
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (a == null || a.getAddress() == null || a.getAddress().length != 4) continue;
                    String ip = a.getHostAddress();
                    if (ip == null || ip.isEmpty()) continue;
                    if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("eth")) {
                        return ip;
                    }
                    if (fallback.isEmpty()) fallback = ip;
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    /** 判断某个 IP 是否属于本机网卡：用于区分「本机广播回环」和「外部设备」。 */
    public static boolean isLocalAddress(String ip) {
        if (ip == null || ip.isEmpty()) return false;
        if ("127.0.0.1".equals(ip) || "0.0.0.0".equals(ip)) return true;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (a != null && ip.equals(a.getHostAddress())) return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
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

    /** 房主侧应答器：收到房号匹配的查询就回一条带真实端口的应答。 */
    public static final class Responder implements Runnable {
        private final String room;
        private final String game;
        private volatile int port;
        private volatile String addr = "";
        private volatile String iid = "";
        private volatile boolean running;
        private DatagramSocket socket;
        private Thread thread;

        public Responder(String room, String game) {
            this.room = room == null ? "" : room;
            this.game = game == null ? "" : game;
        }

        public void update(int port, String addr) {
            this.port = port;
            this.addr = addr == null ? "" : addr;
        }

        /** 房间实例号：随应答/通告下发，客机按它去重，避免同端口新旧房间互相覆盖。 */
        public void setIid(String iid) {
            this.iid = iid == null ? "" : iid;
        }

        public void start() {
            if (running) return;
            running = true;
            thread = new Thread(this, "lan-room-responder");
            thread.setDaemon(true);
            thread.start();
        }

        public void stop() {
            running = false;
            if (socket != null) socket.close();
            socket = null;
            if (thread != null) thread.interrupt();
            thread = null;
        }

        @Override
        public void run() {
            try {
                DatagramSocket s = new DatagramSocket(null);
                s.setReuseAddress(true);
                s.setBroadcast(true);
                s.bind(new InetSocketAddress(QUERY_PORT));
                s.setSoTimeout(500);
                socket = s;
                if (!running) {
                    s.close();
                    socket = null;
                    return;
                }
                Log.i(TAG, "房号查询应答器已启动 port=" + QUERY_PORT);
                byte[] buf = new byte[1024];
                long lastAnnounce = 0;
                boolean loggedAnnounce = false;
                while (running) {
                    // 房主每 3 秒广播一次房间通告：客机不发查询也能从这条通道看到房间
                    long now = System.currentTimeMillis();
                    if (now - lastAnnounce >= 3000) {
                        lastAnnounce = now;
                        int p = port;
                        if (p > 0) {
                            try {
                                JSONObject announce = new JSONObject();
                                announce.put("t", Proto.UDP_ANNOUNCE);
                                announce.put("room", room);
                                announce.put("game", game);
                                announce.put("port", p);
                                announce.put("addr", addr);
                                announce.put("size", boardSize());
                                announce.put("v", Proto.VERSION);
                                if (!iid.isEmpty()) announce.put("iid", iid);
                                byte[] out = announce.toString().getBytes("UTF-8");
                                if (!loggedAnnounce) {
                                    loggedAnnounce = true;
                                    Log.i(TAG, "房主通告 room=" + room + " game=" + game
                                            + " port=" + p + " addr=" + addr
                                            + " body=" + announce);
                                }
                                for (InetAddress target : broadcastAddresses()) {
                                    try {
                                        s.send(new DatagramPacket(out, out.length, target, QUERY_PORT));
                                    } catch (IOException ignored) {
                                    }
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    try {
                        s.receive(packet);
                    } catch (IOException e) {
                        continue;
                    }
                    try {
                        String text = new String(packet.getData(), 0, packet.getLength(), "UTF-8");
                        JSONObject query = new JSONObject(text);
                        if (!Proto.isUdpQuery(Proto.str(query, "t", ""))) continue;
                        // 空房号 = 附近房间列表的广播探测；非空 = 「只填房号」的定向查询
                        String requested = normalize(Proto.str(query, "room", ""));
                        if (!requested.isEmpty() && !requested.equals(normalize(room))) continue;
                        int p = port;
                        if (p <= 0) continue;
                        JSONObject reply = new JSONObject();
                        reply.put("t", Proto.UDP_REPLY);
                        reply.put("room", room);
                        reply.put("game", game);
                        reply.put("port", p);
                        reply.put("addr", addr);
                        reply.put("size", boardSize());
                        reply.put("v", Proto.VERSION);
                        if (!iid.isEmpty()) reply.put("iid", iid);
                        byte[] out = reply.toString().getBytes("UTF-8");
                        s.send(new DatagramPacket(out, out.length, packet.getAddress(), packet.getPort()));
                        Log.i(TAG, "已应答房号查询 from=" + packet.getAddress().getHostAddress());
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "应答器不可用: " + e.getMessage());
            }
        }

        private String boardSize() {
            return GameAdapter.XIANGQI.equals(game) ? "9" : "19";
        }
    }
}
