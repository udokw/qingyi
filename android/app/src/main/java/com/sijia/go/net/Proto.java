package com.sijia.go.net;

import org.json.JSONObject;

import java.util.UUID;

/** 联机协议常量与信封构造。协议版本固定为 1，两端必须一致。 */
public final class Proto {
    public static final int VERSION = 1;
    /** 统一服务类型：各棋种共用一个发现入口，棋种放在 TXT 的 game 字段。 */
    public static final String SERVICE_TYPE = "_qingyi._tcp.";
    /** 与 iOS 保持一致：统一类型 + 棋种专用类型，浏览时全部监听。 */
    public static final String[] BROWSE_TYPES = {
            SERVICE_TYPE, "_qingyi-go._tcp.", "_qingyi-xiangqi._tcp."
    };
    public static final int DEFAULT_PORT = 41234;
    public static final int PORT_TRIES = 11;
    public static final int MAX_FRAME = 65536;

    public static final String HELLO = "hello";
    public static final String HELLO_ACK = "hello_ack";
    public static final String HELLO_REJECT = "hello_reject";
    public static final String LOBBY = "lobby";
    public static final String READY = "ready";
    public static final String GAME_START = "game_start";
    public static final String MOVE = "move";
    public static final String MOVE_ACK = "move_ack";
    public static final String MOVE_REJECT = "move_reject";
    public static final String SNAPSHOT = "state_snapshot";
    public static final String SYNC_REQUEST = "sync_request";
    public static final String UNDO_REQUEST = "undo_request";
    public static final String UNDO_RESPONSE = "undo_response";
    public static final String UNDO_APPLIED = "undo_applied";
    public static final String RESIGN = "resign";
    public static final String DRAW_OFFER = "draw_offer";
    public static final String DRAW_RESPONSE = "draw_response";
    public static final String CLOCK = "clock";
    public static final String PING = "ping";
    public static final String PONG = "pong";
    /** 轻量探活：只校验房间是否仍由该房主在监听，不占用客机槽位、不改变对局状态。 */
    public static final String PROBE = "probe";
    public static final String PROBE_ACK = "probe_ack";
    /** 双方连续各停一手后先进入数子待确认，房主确认后才发 GAME_OVER */
    public static final String SCORE_PENDING = "score_pending";
    public static final String GAME_OVER = "game_over";
    public static final String BYE = "bye";

    /** 握手错误码：房主棋种与客机请求的不一致。 */
    public static final String E_GAME_MISMATCH = "E_GAME_MISMATCH";

    // ---- UDP 发现报文（41235）的类型名与兼容别名 ----
    // 以全小写 + 下划线为准；解析时先归一化（去空白、去下划线/连字符、转小写），
    // 因此 room_reply / roomReply / reply 都认。两端字段表必须一一对应。
    public static final String UDP_QUERY = "room_query";
    public static final String UDP_REPLY = "room_reply";
    public static final String UDP_ANNOUNCE = "room_announce";

    public static String udpKind(String type) {
        String k = type == null ? "" : type.trim().toLowerCase(java.util.Locale.ROOT)
                .replace("_", "").replace("-", "");
        if (k.equals("roomquery") || k.equals("query")) return UDP_QUERY;
        if (k.equals("roomreply") || k.equals("reply")) return UDP_REPLY;
        if (k.equals("roomannounce") || k.equals("announce")) return UDP_ANNOUNCE;
        return k;
    }

    public static boolean isUdpQuery(String type) { return UDP_QUERY.equals(udpKind(type)); }
    public static boolean isUdpReply(String type) { return UDP_REPLY.equals(udpKind(type)); }
    public static boolean isUdpAnnounce(String type) { return UDP_ANNOUNCE.equals(udpKind(type)); }
    public static boolean isUdpAnswer(String type) { return isUdpReply(type) || isUdpAnnounce(type); }

    /** 两端字段对照表（诊断区展示，便于逐个字符比对）。 */
    public static final String UDP_FIELDS = "t:String(room_query|room_reply|room_announce) "
            + "room:String(4位) game:String(go|xiangqi|gomoku|banqi) port:Int size:String(19|9|32) "
            + "addr:String iid:String name:String v:Int ts:Long";

    /** 宽容读取字符串：Number / Boolean 也按文本返回（两端可能在数字/字符串之间摇摆）。 */
    public static String str(JSONObject o, String key, String def) {
        if (o == null) return def;
        Object v = o.opt(key);
        if (v == null || v == JSONObject.NULL) return def;
        if (v instanceof String) {
            String s = ((String) v).trim();
            return s.isEmpty() ? def : s;
        }
        if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
        return def;
    }

    /** 宽容读取整数：Boolean 与字符串形式的数字都接受。 */
    public static int num(JSONObject o, String key, int def) {
        if (o == null) return def;
        Object v = o.opt(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof Boolean) return ((Boolean) v) ? 1 : 0;
        if (v instanceof String) {
            try {
                return (int) Double.parseDouble(((String) v).trim());
            } catch (Exception ignored) {
                return def;
            }
        }
        return def;
    }

    /** 宽容读取长整数：Boolean 与字符串形式的数字都接受。 */
    public static long lng(JSONObject o, String key, long def) {
        if (o == null) return def;
        Object v = o.opt(key);
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof Boolean) return ((Boolean) v) ? 1L : 0L;
        if (v instanceof String) {
            try {
                return (long) Double.parseDouble(((String) v).trim());
            } catch (Exception ignored) {
                return def;
            }
        }
        return def;
    }

    /**
     * 宽容读取布尔：Boolean / Number(0/1) / "1"/"true"/"yes"/"on" 都算真。
     * Android 的 {@code JSONObject.optBoolean} 不会把 Number 转布尔，
     * 历史 bug 就是这样把 hints 永久读成默认值的。
     */
    public static boolean bool(JSONObject o, String key, boolean def) {
        if (o == null) return def;
        Object v = o.opt(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).doubleValue() != 0;
        if (v instanceof String) {
            String s = ((String) v).trim().toLowerCase(java.util.Locale.ROOT);
            if ("1".equals(s) || "true".equals(s) || "yes".equals(s) || "on".equals(s)) return true;
            if ("0".equals(s) || "false".equals(s) || "no".equals(s) || "off".equals(s)) return false;
            return def;
        }
        return def;
    }

    private Proto() {}

    public static JSONObject msg(String type, String room, String from, JSONObject payload) {
        JSONObject o = new JSONObject();
        try {
            o.put("v", VERSION);
            o.put("t", type);
            o.put("sid", UUID.randomUUID().toString().replace("-", "").substring(0, 12));
            o.put("ts", System.currentTimeMillis());
            o.put("seq", 0);
            o.put("room", room);
            o.put("from", from);
            o.put("p", payload == null ? new JSONObject() : payload);
        } catch (Exception ignored) {
        }
        return o;
    }

    public static String type(JSONObject o) {
        return o == null ? "" : o.optString("t", "");
    }

    public static JSONObject p(JSONObject o) {
        JSONObject p = o == null ? null : o.optJSONObject("p");
        return p == null ? new JSONObject() : p;
    }

    /** 4 位纯数字房号（1.1.6 起不再生成含字母的房号）。 */
    public static String newRoomCode() {
        java.util.Random r = new java.util.Random();
        return String.format(java.util.Locale.ROOT, "%04d", r.nextInt(10000));
    }

    public static String colorName(int color) {
        return color == 1 ? "黑" : "白";
    }
}
