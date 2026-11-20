package com.sijia.go.net;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 局域网发现诊断：把「有没有发出去 / 有没有收到 / 为什么丢弃」变成可观测数据。
 *
 * <p>所有计数只在内存中累加，页面与 logcat 共用同一份数据；
 * 固定日志 tag 为 {@code LanDiag}，方便一条命令导出：
 * {@code adb logcat -s LanDiag}。
 */
public final class LanDiag {
    public static final String TAG = "LanDiag";

    /** 被丢弃的包（保留最近若干条，避免无限增长）。 */
    public static final class Drop {
        public final long ts;
        public final String src;
        public final String reason;

        Drop(long ts, String src, String reason) {
            this.ts = ts;
            this.src = src == null ? "?" : src;
            this.reason = reason == null ? "" : reason;
        }

        @Override
        public String toString() {
            return time(ts) + " " + src + " ← " + reason;
        }
    }

    /** 页面展示用的只读快照。 */
    public static final class Snapshot {
        /** 本机当前 IPv4（判定「本机回环」还是「外部来源」的基准）。 */
        public String localIp = "未取到";
        public boolean udpBound;
        public int udpPort;
        public String udpMode = "未启动";
        public int querySent;
        public int replyRecv;
        public int announceRecv;
        /** 收到其他设备的 room_query（别人在找房主，不是候选房间）。 */
        public int queryRecv;
        /** 本机广播被自己 socket 收回的回环包（正常现象）。 */
        public int selfEcho;
        /** 类型名不标准、但带 room+port 被兼容识别的包。 */
        public int compatRecv;
        public int dropped;
        public int found;
        public String lastType = "-";
        public String lastSrc = "-";
        public String lastRoom = "-";
        public String lastGame = "-";
        public String lastIid = "-";
        public long lastAt;
        public String lastBody = "";
        public final List<Drop> drops = new ArrayList<>();
        public final List<String> mdnsDrops = new ArrayList<>();
    }

    private static final LanDiag INSTANCE = new LanDiag();
    private static final int MAX_DROPS = 6;
    private static final int MAX_MDNS_DROPS = 4;

    private String localIp = "未取到";
    private boolean udpBound;
    private int udpPort;
    private String udpMode = "未启动";
    private int querySent;
    private int replyRecv;
    private int announceRecv;
    private int queryRecv;
    private int selfEcho;
    private int compatRecv;
    private int dropped;
    private int found;
    private String lastType = "-";
    private String lastSrc = "-";
    private String lastRoom = "-";
    private String lastGame = "-";
    private String lastIid = "-";
    private long lastAt;
    private String lastBody = "";
    private final List<Drop> drops = new ArrayList<>();
    private final List<String> mdnsDrops = new ArrayList<>();

    public static LanDiag get() {
        return INSTANCE;
    }

    private LanDiag() {}

    public synchronized void reset() {
        localIp = "未取到";
        udpBound = false;
        udpPort = 0;
        udpMode = "未启动";
        querySent = 0;
        replyRecv = 0;
        announceRecv = 0;
        queryRecv = 0;
        selfEcho = 0;
        compatRecv = 0;
        dropped = 0;
        found = 0;
        lastType = "-";
        lastSrc = "-";
        lastRoom = "-";
        lastGame = "-";
        lastIid = "-";
        lastAt = 0;
        lastBody = "";
        drops.clear();
        mdnsDrops.clear();
        Log.i(TAG, "诊断计数已清零");
    }

    public synchronized void udpBind(boolean ok, int port, String mode) {
        udpBound = ok;
        udpPort = port;
        udpMode = mode;
        Log.i(TAG, "UDP 绑定 " + (ok ? "成功" : "失败") + " port=" + port + " mode=" + mode);
    }

    /** 记录本机当前 IPv4：诊断区第一行展示，也是「本机回环」判定的基准。 */
    public synchronized void localIp(String ip) {
        localIp = ip == null || ip.trim().isEmpty() ? "未取到" : ip.trim();
        Log.i(TAG, "本机 IPv4=" + localIp);
    }

    public synchronized String localIp() {
        return localIp;
    }

    public synchronized void querySent(int count) {
        querySent += count;
    }

    /** 收到其他设备主动发出的 room_query：它不是候选房间，但能证明「广播到得了本机」。 */
    public synchronized void queryReceived(String src) {
        queryRecv++;
        Log.i(TAG, "收到外部 room_query src=" + src);
    }

    /** 本机发出的广播被自己的 socket 收回（UDP 广播回环），属正常现象。 */
    public synchronized void selfEcho(String src) {
        selfEcho++;
        Log.i(TAG, "本机广播回环 src=" + src);
    }

    /** 类型名不标准，但带 room+port，按房间通告兼容处理。 */
    public synchronized void compat(String src, String rawType, String body) {
        compatRecv++;
        Log.w(TAG, "非标准类型 " + rawType + " 按房间通告兼容处理 src=" + src + " 原文=" + body);
    }

    public synchronized void packetReceived(String type, String src, String room,
                                            String game, String iid, String body, int length) {
        if ("room_reply".equals(type)) replyRecv++;
        else if ("room_announce".equals(type)) announceRecv++;
        lastType = type == null ? "-" : type;
        lastSrc = src == null ? "-" : src;
        lastRoom = room == null || room.isEmpty() ? "-" : room;
        lastGame = game == null || game.isEmpty() ? "-" : game;
        lastIid = iid == null || iid.isEmpty() ? "-" : iid;
        lastAt = System.currentTimeMillis();
        lastBody = "len=" + length + " " + body;
        Log.i(TAG, "收到 " + lastType + " src=" + lastSrc + " room=" + lastRoom
                + " game=" + lastGame + " iid=" + lastIid + " " + lastBody);
    }

    public synchronized void found(String room, String src) {
        found++;
        Log.i(TAG, "发现房间 room=" + room + " src=" + src);
    }

    public synchronized void drop(String src, String reason) {
        dropped++;
        Drop d = new Drop(System.currentTimeMillis(), src, reason);
        drops.add(0, d);
        while (drops.size() > MAX_DROPS) drops.remove(drops.size() - 1);
        Log.w(TAG, "丢弃 src=" + d.src + " 原因=" + reason);
    }

    /** mDNS 通道的丢弃（能力受限，只记录原因，不影响 UDP 计数）。 */
    public synchronized void mdnsDrop(String reason) {
        mdnsDrops.add(0, time(System.currentTimeMillis()) + " " + reason);
        while (mdnsDrops.size() > MAX_MDNS_DROPS) mdnsDrops.remove(mdnsDrops.size() - 1);
        Log.w(TAG, "mDNS 丢弃：" + reason);
    }

    public synchronized void note(String message) {
        Log.i(TAG, message);
    }

    public synchronized Snapshot snapshot() {
        Snapshot s = new Snapshot();
        s.localIp = localIp;
        s.udpBound = udpBound;
        s.udpPort = udpPort;
        s.udpMode = udpMode;
        s.querySent = querySent;
        s.replyRecv = replyRecv;
        s.announceRecv = announceRecv;
        s.queryRecv = queryRecv;
        s.selfEcho = selfEcho;
        s.compatRecv = compatRecv;
        s.dropped = dropped;
        s.found = found;
        s.lastType = lastType;
        s.lastSrc = lastSrc;
        s.lastRoom = lastRoom;
        s.lastGame = lastGame;
        s.lastIid = lastIid;
        s.lastAt = lastAt;
        s.lastBody = lastBody;
        s.drops.addAll(drops);
        s.mdnsDrops.addAll(mdnsDrops);
        return s;
    }

    /** 页面展示用多行文本。 */
    public synchronized String report() {
        Snapshot s = snapshot();
        StringBuilder sb = new StringBuilder();
        sb.append("本机 IPv4：").append(s.localIp).append("\n");
        sb.append("UDP 41235：").append(s.udpBound ? "bind 成功" : "bind 失败")
                .append("（本地端口 ").append(s.udpPort > 0 ? s.udpPort : "无")
                .append("，").append(s.udpMode).append("）\n");
        sb.append("查询发出：").append(s.querySent).append(" 次\n");
        sb.append("收到的 room_reply：").append(s.replyRecv)
                .append(" ｜ room_announce：").append(s.announceRecv).append("\n");
        sb.append("外部 room_query：").append(s.queryRecv)
                .append(" ｜ 本机回环：").append(s.selfEcho)
                .append(" ｜ 兼容类型：").append(s.compatRecv).append("\n");
        sb.append("成功入列房间：").append(s.found).append(" ｜ 丢弃包：").append(s.dropped).append("\n");
        if (s.lastAt > 0) {
            sb.append("最后收包：").append(time(s.lastAt)).append(" ").append(s.lastType).append("\n");
            sb.append("  来源 ").append(s.lastSrc)
                    .append("｜room ").append(s.lastRoom)
                    .append("｜game ").append(s.lastGame)
                    .append("｜iid ").append(s.lastIid).append("\n");
            sb.append("  原文 ").append(s.lastBody).append("\n");
        } else {
            sb.append("最后收包：尚未收到任何 UDP 包\n");
        }
        sb.append("说明：广播 socket 会收到自己发出的包（UDP 广播回环），")
                .append("来源为本机 IPv4 的 room_query 已单独计入「本机回环」，不算异常丢弃。\n");
        sb.append("字段表：").append(Proto.UDP_FIELDS).append("\n");
        if (!s.drops.isEmpty()) {
            sb.append("丢弃明细：\n");
            for (Drop d : s.drops) sb.append("  ").append(d).append("\n");
        }
        if (!s.mdnsDrops.isEmpty()) {
            sb.append("mDNS：\n");
            for (String m : s.mdnsDrops) sb.append("  ").append(m).append("\n");
        }
        return sb.toString().trim();
    }

    static String time(long ts) {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(ts));
    }
}
