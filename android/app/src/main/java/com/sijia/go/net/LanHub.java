package com.sijia.go.net;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.sijia.go.BuildConfig;
import com.sijia.go.GomokuGame;
import com.sijia.go.GoGame;
import com.sijia.go.XiangqiGame;
import com.sijia.go.banqi.BanqiGame;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 联机运行时单例：持有 Host/Guest、权威会话与时钟，跨 Activity 存活。
 * 棋种差异全部交给 GameAdapter；本类只负责房间、握手、同步与计时。
 * 所有回调与棋局修改都在主线程串行执行。
 */
public class LanHub {
    private static final String TAG = "LanHub";
    /** 单个候选地址的握手等待时间。 */
    private static final long HELLO_WAIT_MS = 6000;

    public static final String ROLE_HOST = "host";
    public static final String ROLE_GUEST = "guest";

    public static final int IDLE = 0;
    public static final int HOSTING = 1;
    public static final int CONNECTING = 2;
    public static final int LOBBY = 3;
    public static final int PLAYING = 4;
    public static final int FINISHED = 5;
    public static final int ERROR = 6;

    public interface Listener {
        default void onStateChanged() {}

        default void onGameStart() {}

        default void onMove() {}

        default void onClock() {}

        default void onGameOver(String result, String detail) {}

        default void onUndoRequest(String requester) {}

        default void onNotice(String message) {}

        default void onError(String message) {}
    }

    private static LanHub instance;

    public static LanHub get() {
        if (instance == null) instance = new LanHub();
        return instance;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private Context app;
    private String role = "";
    private LanSession session;
    private LanHost host;
    private volatile LanClient client;
    private LanDiscovery discovery;
    /** 「只填房号」加入时的 UDP 广播应答器（房主侧）。 */
    private LanRoomQuery.Responder queryResponder;

    private volatile int state = IDLE;
    private String roomCode = "";
    private String localName = "棋友";
    private String opponentName = "对手";
    /** 棋种提示：go / xiangqi；手动加入时为空，由房主在 hello_ack 里告知。 */
    private String gameId = "";
    /** 座位号：1 = 先手（围棋黑 / 象棋红），2 = 后手（围棋白 / 象棋黑）。 */
    private int myColor = 1;
    private int hostColor = 1;
    private int mainSec = 1200;
    private int byoYomiSec = 30;
    private int periods = 3;
    /** 房间规则：是否允许落点 / 可吃子提示。由房主建房时决定，随房间下发。 */
    private boolean hintsEnabled = true;
    /** 五子棋房间规则：黑棋禁手（长连/四四/三三）。由房主建房时决定，随房间下发。 */
    private boolean renjuEnabled = false;

    private LanClock clock;
    private JSONObject guestClock;
    private long guestClockAt;
    private long lastClockPush;
    /** 断线后冻结客机时钟显示，避免结束时用陈旧快照补扣一大段。 */
    private volatile boolean clockFrozen;

    private boolean pendingMove;
    private String pendingUndoFrom;
    private int pendingUndoCount = 1;

    /** 围棋双方连续各停一手后为 true：进入数子确认，房主确认后才终局。 */
    private volatile boolean scorePending;
    private JSONObject pendingScore;
    private String resultCode = "";
    private String resultDetail = "";
    private String resultReason = "";
    private String hostAddress = "";
    private String lastError = "";
    private int port;

    private Runnable ticker;

    /** 断线宽限期：期间暂停计时，超过宽限期判负。与大厅房间清理是两个独立机制。 */
    public static final long RECONNECT_GRACE_MS = 120000;
    /** 超过该时间没有任何消息即认为链路中断（房主侧靠 PING 保活）。 */
    private static final long LINK_IDLE_MS = 13000;
    private static final long LINK_PING_MS = 5000;

    /** 连接已中断但会话保留，等待重连。 */
    private volatile boolean linkDown;
    private long lastRecvAt;
    private long lastPingAt;
    private Runnable graceTask;
    private Runnable linkWatch;
    /** 每次停止/重连自增，用于终止旧的守护与重连线程。 */
    private volatile int linkEpoch;
    private String lastHostIp = "";
    private volatile int lastHostPort;
    /** 本机标识，便于房主区分重连与陌生连接。 */
    private final String clientId = java.util.UUID.randomUUID().toString();
    /** 本次房主注册实例号，写入 TXT 并随探活应答返回。 */
    private String instanceId = "";

    /** 加入流程中等待 hello_ack 的同步状态，由主线程回调唤醒连接线程。 */
    private final Object joinLock = new Object();
    /** 每次连接尝试的唯一序号，用于丢弃旧连接的迟到回调。 */
    private final java.util.concurrent.atomic.AtomicInteger attemptSeq =
            new java.util.concurrent.atomic.AtomicInteger();
    private boolean joinAck;
    private boolean joinRejected;
    private String joinRejectMessage = "";
    private String joinRejectCode = "";
    private String joinCloseReason = "";

    private LanHub() {}

    // ---------------- 监听 ----------------

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    // ---------------- 状态读取 ----------------

    public LanSession session() { return session; }
    public GameAdapter rules() { return session == null ? null : session.rules; }

    /** 仅围棋可用，象棋返回 null。 */
    public GoGame game() {
        return session != null && session.rules instanceof GoRules
                ? ((GoRules) session.rules).game() : null;
    }

    /** 仅象棋可用，围棋返回 null。 */
    public XiangqiGame xiangqi() {
        return session != null && session.rules instanceof XiangqiRules
                ? ((XiangqiRules) session.rules).game() : null;
    }

    /** 仅五子棋可用，其他棋种返回 null。 */
    public GomokuGame gomoku() {
        return session != null && session.rules instanceof GomokuRules
                ? ((GomokuRules) session.rules).game() : null;
    }

    /** 仅暗棋可用，其他棋种返回 null。 */
    public BanqiGame banqi() {
        return session != null && session.rules instanceof BanqiRules
                ? ((BanqiRules) session.rules).game() : null;
    }

    public int state() { return state; }
    public boolean isHost() { return ROLE_HOST.equals(role); }
    public boolean isPlaying() { return state == PLAYING; }

    /** 对局中连接中断、正在等待重连（棋盘与时钟保留）。 */
    public boolean linkDown() { return linkDown; }
    public boolean isMyTurn() { return session != null && session.rules.turn() == myColor; }
    public int turn() { return session == null ? 1 : session.rules.turn(); }
    public int myColor() { return myColor; }
    public int moveNo() { return session == null ? 0 : session.moveNo; }
    public String roomCode() { return roomCode; }
    public String localName() { return localName; }
    public String opponentName() { return opponentName; }
    public String hostAddress() { return hostAddress; }
    public int port() { return port; }
    public boolean pendingMove() { return pendingMove; }
    public boolean hasGuest() { return host != null && host.hasGuest(); }
    /** 房间规则：客机也按房主下发的值渲染提示。 */
    public boolean hintsEnabled() { return hintsEnabled; }
    /** 五子棋房间规则：客机也按房主下发的值执行禁手判定。 */
    public boolean renjuEnabled() { return renjuEnabled; }
    public String resultCode() { return resultCode; }
    public String resultDetail() { return resultDetail; }
    public boolean scorePending() { return scorePending; }

    /** 展示层中文结果文案；协议字段 result/detail/reason 保持不变。 */
    public String resultText() {
        return ResultText.of(gameId(), resultCode, resultReason, resultDetail);
    }
    public String lastError() { return lastError; }
    public double komi() { return session == null ? 7.5 : session.komi; }
    public boolean timerEnabled() { return mainSec > 0; }
    public int mainSec() { return mainSec; }
    public int boardSize() { return session == null ? 19 : session.rules.boardSize(); }
    public String gameId() {
        if (session != null) return session.gameId;
        return gameId == null || gameId.isEmpty() ? GameAdapter.GO : gameId;
    }
    public String gameLabel() { return GameAdapter.create(gameId()).label(); }
    public String myColorName() { return colorName(myColor); }
    public GameAdapter.MoveInfo lastMove() { return session == null ? null : session.lastMove(); }

    public String colorChar(int color) {
        return session != null ? session.rules.colorChar(color)
                : GameAdapter.create(gameId()).colorChar(color);
    }

    public String colorName(int color) {
        return session != null ? session.rules.colorName(color)
                : GameAdapter.create(gameId()).colorName(color);
    }

    // ---------------- 建房 / 加入 ----------------

    public void hostRoom(Context ctx, String gameId, String name, int color,
                         int mainSec, int byoYomiSec, int periods) {
        hostRoom(ctx, gameId, name, color, mainSec, byoYomiSec, periods, true);
    }

    public void hostRoom(Context ctx, String gameId, String name, int color,
                         int mainSec, int byoYomiSec, int periods, boolean hints) {
        hostRoom(ctx, gameId, name, color, mainSec, byoYomiSec, periods, hints, false);
    }

    /** 带五子棋禁手选项的建房。renju 仅对 gomoku 生效。 */
    public void hostRoom(Context ctx, String gameId, String name, int color,
                         int mainSec, int byoYomiSec, int periods, boolean hints, boolean renju) {
        stop();
        // 上一轮的关闭任务可能还在后台执行，新一轮会话不参与它的互斥
        stopping = false;
        app = ctx.getApplicationContext();
        role = ROLE_HOST;
        this.gameId = GameAdapter.XIANGQI.equals(gameId) ? GameAdapter.XIANGQI
                : GameAdapter.GOMOKU.equals(gameId) ? GameAdapter.GOMOKU
                : GameAdapter.BANQI.equals(gameId) ? GameAdapter.BANQI : GameAdapter.GO;
        localName = safe(name, "棋友");
        hostColor = color == 2 ? 2 : 1;
        myColor = hostColor;
        roomCode = Proto.newRoomCode();
        this.mainSec = Math.max(0, mainSec);
        this.byoYomiSec = Math.max(0, byoYomiSec);
        this.periods = Math.max(1, periods);
        this.hintsEnabled = hints;
        this.renjuEnabled = renju && GameAdapter.GOMOKU.equals(this.gameId);
        session = new LanSession(roomCode, this.gameId);
        if (session.rules instanceof GomokuRules)
            ((GomokuRules) session.rules).setRenju(this.renjuEnabled);
        session.reset(session.rules instanceof GoRules ? GoRules.DEFAULT_KOMI : 0);
        clock = new LanClock(this.mainSec, this.byoYomiSec, this.periods);
        host = new LanHost();
        try {
            port = host.start(Proto.DEFAULT_PORT, Proto.PORT_TRIES, hostCallback);
        } catch (IOException e) {
            state = ERROR;
            lastError = "创建房间失败：" + e.getMessage();
            notifyError(lastError);
            return;
        }
        String ip = NetUtil.localIpv4(app);
        hostAddress = ip + ":" + port;
        // 本次注册实例号：客机按它去重，避免同端口的新房间被旧 mDNS 条目覆盖
        instanceId = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        discovery = new LanDiscovery();
        discovery.register(app, "QY-" + roomCode + "-" + session.size, port,
                roomCode, session.size, ip, this.gameId, instanceId, hintsEnabled);
        // 只填房号也能找到房间：UDP 广播应答，回包带真实端口
        queryResponder = new LanRoomQuery.Responder(roomCode, this.gameId);
        queryResponder.update(port, ip);
        queryResponder.setIid(instanceId);
        queryResponder.start();
        // 探活应答：只报告房间身份，不触碰对局会话；探活连接不会占用客机槽位
        host.setProbeInfo(() -> {
            JSONObject p = new JSONObject();
            try {
                p.put("room", roomCode);
                p.put("game", gameId);
                p.put("iid", instanceId);
                p.put("pid", android.os.Process.myPid());
                p.put("ts", System.currentTimeMillis());
                p.put("hints", hintsEnabled ? 1 : 0);
                p.put("renju", renjuEnabled ? 1 : 0);
                p.put("state", state == PLAYING ? "playing" : "waiting");
            } catch (Exception ignored) {
            }
            return p;
        });
        state = HOSTING;
        Log.i(TAG, "建房 game=" + this.gameId + " room=" + roomCode + " addr=" + hostAddress
                + " iid=" + instanceId);
        notifyState();
    }

    public void joinRoom(Context ctx, String ip, int port, String code, String name) {
        joinRoom(ctx, new String[]{ip + ":" + port}, code, name, null);
    }

    public void joinRoom(Context ctx, String ip, int port, String code, String name, String game) {
        joinRoom(ctx, new String[]{ip + ":" + port}, code, name, game);
    }

    /**
     * 依次尝试候选地址（TXT 优先 + SRV 回退），每个地址连上后发 hello 并等待 hello_ack。
     * 任一步失败都会换下一个候选；全部失败时进入 ERROR 并把真实原因暴露到 UI。
     */
    public void joinRoom(Context ctx, String[] candidates, String code, String name) {
        joinRoom(ctx, candidates, code, name, null);
    }

    public void joinRoom(Context ctx, String[] candidates, String code, String name, String game) {
        stop();
        // 上一轮的关闭任务可能还在后台执行，新一轮会话不参与它的互斥
        stopping = false;
        app = ctx.getApplicationContext();
        role = ROLE_GUEST;
        localName = safe(name, "棋友");
        roomCode = safe(code, "").trim().toUpperCase();
        gameId = game == null ? "" : game.trim();
        state = CONNECTING;
        hostAddress = candidates != null && candidates.length > 0 ? candidates[0] : "";
        notifyState();

        final String[] list = candidates == null ? new String[0] : candidates.clone();
        new Thread(() -> connectLoop(list), "lan-connect").start();
    }

    private void connectLoop(String[] candidates) {
        String lastReason = "没有可用的房主地址";
        // 逐个端口扫描时给一个总时限，避免连不上的地址把 11 个端口 × 6 秒全耗完
        long deadline = SystemClock.elapsedRealtime() + 20_000;
        for (String cand : candidates) {
            if (SystemClock.elapsedRealtime() > deadline) {
                lastReason = "连接超时，已尝试部分地址/端口";
                break;
            }
            if (state != CONNECTING) {
                Log.i(TAG, "加入流程已取消，停止尝试");
                return;
            }
            String[] hp = splitHostPort(cand);
            if (hp == null) {
                lastReason = "地址格式错误：" + cand;
                Log.w(TAG, lastReason);
                continue;
            }
            final String ip = hp[0];
            final int p;
            try {
                p = Integer.parseInt(hp[1]);
            } catch (Exception e) {
                lastReason = "端口非法：" + cand;
                continue;
            }
            Log.i(TAG, "尝试连接 " + ip + ":" + p);
            main.post(() -> {
                hostAddress = ip + ":" + p;
                notifyState();
            });

            resetJoinWait();
            final int seq = attemptSeq.incrementAndGet();
            final LanClient c = new LanClient();
            client = c;
            LanClient.Callback attemptCb = new LanClient.Callback() {
                @Override
                public void onMessage(JSONObject msg) {
                    // 旧连接或已被重连替换的连接，迟到的消息一律丢弃
                    if (seq != attemptSeq.get() || client != c) return;
                    clientCallback.onMessage(msg);
                }

                @Override
                public void onClosed(String reason) {
                    if (seq != attemptSeq.get() || client != c) return;
                    clientCallback.onClosed(reason);
                }
            };
            try {
                c.connect(ip, p, attemptCb);
            } catch (Exception e) {
                lastReason = "连接 " + ip + ":" + p + " 失败：" + shortMsg(e);
                Log.e(TAG, lastReason, e);
                c.close();
                continue;
            }
            if (state != CONNECTING) {
                c.close();
                return;
            }
            JSONObject payload = new JSONObject();
            try {
                payload.put("protocolVersion", Proto.VERSION);
                payload.put("roomCode", roomCode);
                payload.put("deviceName", localName);
                payload.put("platform", "android");
                payload.put("appVersion", BuildConfig.VERSION_NAME);
                payload.put("clientId", clientId);
                // 从房间列表进来时已知棋种；手动 IP 时不带，由房主 hello_ack 决定
                if (!gameId.isEmpty()) payload.put("game", gameId);
            } catch (Exception ignored) {
            }
            if (!c.send(Proto.msg(Proto.HELLO, roomCode, ROLE_GUEST, payload))) {
                lastReason = "向 " + ip + ":" + p + " 发送 hello 失败";
                Log.e(TAG, lastReason);
                attemptSeq.incrementAndGet();
                if (client == c) client = null;
                c.close();
                continue;
            }

            String why = waitHello();
            if (joinAck) {
                lastHostIp = ip;
                lastHostPort = p;
                Log.i(TAG, "握手成功 " + ip + ":" + p);
                return;
            }
            attemptSeq.incrementAndGet();
            if (client == c) client = null;
            c.close();
            if (state != CONNECTING) return;
            if (joinRejected) {
                final String msg = joinRejectMessage.isEmpty() ? "房主拒绝加入" : joinRejectMessage;
                Log.w(TAG, "房主拒绝加入：" + msg);
                main.post(() -> failJoin(msg));
                return;
            }
            lastReason = why == null || why.isEmpty()
                    ? "连接 " + ip + ":" + p + " 后未收到握手回应" : why;
            Log.w(TAG, "本次尝试失败：" + lastReason);
        }
        final String reason = lastReason;
        main.post(() -> failJoin("连接失败：" + reason));
    }

    private void failJoin(String message) {
        if (state != CONNECTING) return;
        state = ERROR;
        lastError = message;
        Log.e(TAG, message);
        notifyError(lastError);
        notifyState();
    }

    /** 等待主线程回调 hello_ack / hello_reject / 连接关闭，返回失败原因（成功时为 null）。 */
    private String waitHello() {
        long deadline = SystemClock.elapsedRealtime() + HELLO_WAIT_MS;
        synchronized (joinLock) {
            while (!joinAck && !joinRejected && joinCloseReason.isEmpty()) {
                long left = deadline - SystemClock.elapsedRealtime();
                if (left <= 0) return "等待 hello_ack 超时（" + HELLO_WAIT_MS + "ms）";
                try {
                    joinLock.wait(left);
                } catch (InterruptedException e) {
                    return "加入流程被中断";
                }
            }
            return joinCloseReason;
        }
    }

    private void resetJoinWait() {
        synchronized (joinLock) {
            joinAck = false;
            joinRejected = false;
            joinRejectMessage = "";
            joinRejectCode = "";
            joinCloseReason = "";
        }
    }

    private static String shortMsg(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /**
     * 读取布尔字段。两端都会把 hints 以 1/0 整数下发，
     * 而 Android 的 JSONObject.optBoolean 不把 Number 转布尔，会直接返回默认值，
     * 导致客机忽略房主的房间规则；这里统一兼容 Boolean / Number / "1" / "true"。
     */
    private static boolean flag(JSONObject p, String key, boolean def) {
        return Proto.bool(p, key, def);
    }

    /**
     * 手动地址 → 候选列表。只有显式写了端口才能直接连；
     * 裸 IP 不再假设端口区间，改为先用房号走 UDP 查询拿到房主真实端口
     * （房主可能被系统分配到任意端口，猜区间必然失败）。
     */
    public static String[] manualCandidates(String addr) {
        if (!explicitPort(addr)) return new String[0];
        String[] hp = splitHostPort(addr);
        return hp == null ? new String[0] : new String[]{hp[0] + ":" + hp[1]};
    }

    /** 地址里是否显式写了端口（裸 IPv6 视为未写）。 */
    private static boolean explicitPort(String s) {
        String v = s == null ? "" : s.trim();
        if (v.isEmpty()) return false;
        if (v.startsWith("[")) {
            int end = v.indexOf(']');
            return end > 0 && v.substring(end + 1).startsWith(":");
        }
        int first = v.indexOf(':');
        return first >= 0 && first == v.lastIndexOf(':') && first < v.length() - 1;
    }

    /** 解析 host:port / [v6]:port / 裸 IPv6（默认端口）。 */
    private static String[] splitHostPort(String s) {
        if (s == null) return null;
        String v = s.trim();
        if (v.isEmpty()) return null;
        if (v.startsWith("[")) {
            int end = v.indexOf(']');
            if (end < 0) return null;
            String h = v.substring(1, end);
            String rest = v.substring(end + 1);
            String ps = rest.startsWith(":") ? rest.substring(1) : String.valueOf(Proto.DEFAULT_PORT);
            return new String[]{h, ps};
        }
        int cut = v.lastIndexOf(':');
        if (cut < 0) return new String[]{v, String.valueOf(Proto.DEFAULT_PORT)};
        if (v.indexOf(':') != cut) return new String[]{v, String.valueOf(Proto.DEFAULT_PORT)};
        String h = v.substring(0, cut).trim();
        String ps = v.substring(cut + 1).trim();
        if (h.isEmpty() || ps.isEmpty()) return null;
        return new String[]{h, ps};
    }

    // ---------------- 开局 ----------------

    public void startGame() {
        if (!isHost() || state != LOBBY || !hasGuest()) return;
        session.reset(session.rules instanceof GoRules ? GoRules.DEFAULT_KOMI : 0);
        scorePending = false;
        pendingScore = null;
        clock = new LanClock(mainSec, byoYomiSec, periods);
        boolean timed = clock.active();
        if (timed) {
            clock.start(0); // 先手先走
            lastClockPush = SystemClock.elapsedRealtime();
        }

        JSONObject p = new JSONObject();
        try {
            p.put("game", session.gameId);
            p.put("gameId", session.matchId);
            p.put("size", session.rules.boardSize());
            p.put("komi", session.komi);
            p.put("handicap", 0);
            p.put("handicapPoints", new JSONArray());
            p.put("ruleset", session.rules instanceof GoRules ? "chinese-simple-ko"
                    : session.rules instanceof GomokuRules ? "freestyle"
                    : session.rules instanceof BanqiRules ? "banqi" : "xiangqi");
            p.put("toMove", colorChar(session.rules.turn()));
            JSONArray seats = new JSONArray();
            JSONObject h = new JSONObject();
            h.put("seat", 0);
            h.put("name", localName);
            h.put("color", colorChar(hostColor));
            h.put("kind", "human");
            seats.put(h);
            JSONObject g = new JSONObject();
            g.put("seat", 1);
            g.put("name", opponentName);
            g.put("color", colorChar(3 - hostColor));
            g.put("kind", "human");
            seats.put(g);
            p.put("seats", seats);
            JSONObject tc = new JSONObject();
            tc.put("mode", timed ? "byoyomi" : "none");
            tc.put("mainSec", mainSec);
            tc.put("byoYomiSec", byoYomiSec);
            tc.put("periods", periods);
            tc.put("incrementSec", 0);
            p.put("timeControl", tc);
            p.put("startAtHostMs", System.currentTimeMillis());
            p.put("hints", hintsEnabled ? 1 : 0);
            p.put("renju", renjuEnabled ? 1 : 0);
        } catch (Exception ignored) {
        }

        if (!host.send(Proto.msg(Proto.GAME_START, roomCode, ROLE_HOST, p))) {
            notifyError("发送开局消息失败，请让对手重新加入房间");
            return;
        }
        state = PLAYING;
        if (timed) startTicker();
        lastRecvAt = SystemClock.elapsedRealtime();
        lastPingAt = 0;
        startLinkWatch();
        notifyState();
        notifyGameStart();
    }

    // ---------------- 落子 ----------------

    public boolean requestMove(int x, int y) {
        if (state != PLAYING || scorePending || session == null
                || !(GameAdapter.GO.equals(session.gameId)
                || GameAdapter.GOMOKU.equals(session.gameId))) return false;
        JSONObject mv = new JSONObject();
        try {
            mv.put("pass", false);
            mv.put("x", x);
            mv.put("y", y);
        } catch (Exception ignored) {
        }
        return submitMove(mv);
    }

    public boolean requestPass() {
        if (state != PLAYING || scorePending || session == null
                || !GameAdapter.GO.equals(session.gameId)) return false;
        JSONObject mv = new JSONObject();
        try {
            mv.put("pass", true);
            mv.put("x", -1);
            mv.put("y", -1);
        } catch (Exception ignored) {
        }
        return submitMove(mv);
    }

    public boolean requestXiangqiMove(int fx, int fy, int tx, int ty) {
        if (state != PLAYING || session == null
                || !GameAdapter.XIANGQI.equals(session.gameId)) return false;
        JSONObject mv = new JSONObject();
        try {
            mv.put("fx", fx);
            mv.put("fy", fy);
            mv.put("tx", tx);
            mv.put("ty", ty);
        } catch (Exception ignored) {
        }
        return submitMove(mv);
    }

    /** 暗棋翻子：房主掌握真实身份，客机只发送坐标。 */
    public boolean requestBanqiFlip(int x, int y) {
        if (state != PLAYING || session == null
                || !GameAdapter.BANQI.equals(session.gameId)
                || session.rules.turn() != myColor) return false;
        JSONObject mv = new JSONObject();
        try {
            mv.put("flip", true);
            mv.put("x", x);
            mv.put("y", y);
        } catch (Exception ignored) {
        }
        return submitMove(mv);
    }

    /** 暗棋走子/吃子。 */
    public boolean requestBanqiMove(int fx, int fy, int tx, int ty) {
        if (state != PLAYING || session == null
                || !GameAdapter.BANQI.equals(session.gameId)
                || session.rules.turn() != myColor) return false;
        JSONObject mv = new JSONObject();
        try {
            mv.put("flip", false);
            mv.put("fx", fx);
            mv.put("fy", fy);
            mv.put("tx", tx);
            mv.put("ty", ty);
        } catch (Exception ignored) {
        }
        return submitMove(mv);
    }

    /** 本地已做合法性预检，这里只负责轮次判断与发送。 */
    private boolean submitMove(JSONObject mv) {
        if (linkDown) {
            notifyNotice("连接中断，正在重连，请稍候…");
            return false;
        }
        if (session.rules.turn() != myColor) {
            notifyNotice("还没轮到你走棋");
            return false;
        }
        if (isHost()) {
            return hostApply(myColor, mv);
        }
        if (pendingMove) return false;
        JSONObject p = session.rules.movePayload(myColor, mv, session.hash());
        try {
            p.put("moveNo", session.moveNo + 1);
            p.put("color", colorChar(myColor));
        } catch (Exception ignored) {
        }
        if (!sendToHost(Proto.msg(Proto.MOVE, roomCode, ROLE_GUEST, p))) {
            notifyNotice("落子发送失败，请检查连接");
            return false;
        }
        pendingMove = true;
        notifyState();
        return true;
    }

    private boolean hostApply(int seat, JSONObject mv) {
        GameAdapter.ApplyResult r = session.applyAuthority(seat, mv, 0, null);
        if (!r.ok) {
            notifyNotice(r.message);
            return false;
        }
        if (clock != null && clock.active()) {
            clock.onMove(seat - 1);
            if (clock.timeoutColor() >= 0) {
                hostTimeout(clock.timeoutColor());
                return true;
            }
        }
        sendAck(r);
        notifyMove();
        afterMove();
        return true;
    }

    private void sendAck(GameAdapter.ApplyResult r) {
        JSONObject p = new JSONObject();
        try {
            p.put("moveNo", r.moveNo);
            p.put("pass", r.pass);
            p.put("x", r.x);
            p.put("y", r.y);
            p.put("flip", r.flip);
            p.put("piece", r.piece);
            p.put("seat1Color", r.seat1Color);
            p.put("seat2Color", r.seat2Color);
            p.put("fx", r.fx);
            p.put("fy", r.fy);
            p.put("tx", r.tx);
            p.put("ty", r.ty);
            p.put("captured", r.captured);
            p.put("nextTurn", session.rules.colorChar(r.nextTurn));
            p.put("hash", r.hash);
            if (clock != null && clock.active()) p.put("clock", clock.snapshot(session.moveNo));
        } catch (Exception ignored) {
        }
        host.send(Proto.msg(Proto.MOVE_ACK, roomCode, ROLE_HOST, p));
        if (clock != null && clock.active()) notifyClock();
    }

    /** 房主每手之后检查棋规是否已自动结束，或围棋双方停一手。 */
    private void afterMove() {
        if (session.rules.isOver()) {
            hostFinishPayload(session.rules.result());
            return;
        }
        if (session.rules.supportsScore() && session.consecutivePasses >= 2) {
            // 双方连续各停一手：先给双方预览数子结果，房主确认后才真正终局
            hostScorePending(session.rules.scoreResult());
        }
    }

    private void hostScorePending(JSONObject res) {
        if (res == null || scorePending) return;
        pendingScore = res;
        scorePending = true;
        resultCode = res.optString("result", "");
        resultDetail = res.optString("detail", "双方各停一手");
        resultReason = res.optString("reason", "");
        JSONObject p = new JSONObject();
        try {
            p.put("game", session.gameId);
            p.put("moveNo", session.moveNo);
            p.put("komi", session.rules.komi());
            java.util.Iterator<String> it = res.keys();
            while (it.hasNext()) {
                String k = it.next();
                p.put(k, res.get(k));
            }
        } catch (Exception ignored) {
        }
        host.send(Proto.msg(Proto.SCORE_PENDING, roomCode, ROLE_HOST, p));
        notifyNotice("双方各停一手，请确认数子结果");
        notifyMove();
        notifyState();
    }

    /** 房主确认数子结果，之后才广播 GAME_OVER。 */
    public void confirmScore() {
        if (!isHost() || !scorePending || pendingScore == null) return;
        JSONObject res = pendingScore;
        scorePending = false;
        pendingScore = null;
        hostFinishPayload(res);
    }

    // ---------------- 悔棋 ----------------

    public void requestUndo() {
        if (state != PLAYING || session == null || session.moveNo <= 0) return;
        if (pendingUndoFrom != null) return;
        pendingUndoFrom = isHost() ? ROLE_HOST : ROLE_GUEST;
        pendingUndoCount = 1;
        try {
            JSONObject p = new JSONObject();
            p.put("count", 1);
            p.put("moveNo", session.moveNo);
            send(Proto.msg(Proto.UNDO_REQUEST, roomCode, isHost() ? ROLE_HOST : ROLE_GUEST, p));
            notifyNotice("已发送悔棋请求");
        } catch (Exception ignored) {
        }
    }

    public void respondUndo(boolean accept) {
        String from = pendingUndoFrom;
        if (from == null) return;
        pendingUndoFrom = null;
        if (isHost()) {
            if (accept) {
                hostUndo(pendingUndoCount);
            } else {
                try {
                    JSONObject p = new JSONObject();
                    p.put("accept", false);
                    host.send(Proto.msg(Proto.UNDO_RESPONSE, roomCode, ROLE_HOST, p));
                } catch (Exception ignored) {
                }
            }
        } else {
            try {
                JSONObject p = new JSONObject();
                p.put("accept", accept);
                sendToHost(Proto.msg(Proto.UNDO_RESPONSE, roomCode, ROLE_GUEST, p));
            } catch (Exception ignored) {
            }
            if (!accept) notifyNotice("已拒绝悔棋");
        }
    }

    private void hostUndo(int count) {
        if (session == null) return;
        // 悔棋改变了局面，之前的数子预览作废，避免确认后按旧比分结束
        scorePending = false;
        pendingScore = null;
        session.undo(Math.max(1, count));
        if (clock != null) clock.setTurn(session.rules.turn() == 1 ? 0 : 1);
        sendUndoApplied();
        notifyMove();
    }

    private void sendUndoApplied() {
        JSONObject p = new JSONObject();
        try {
            p.put("moveNo", session.moveNo);
            p.put("hash", session.hash());
            p.put("snapshot", session.snapshot());
        } catch (Exception ignored) {
        }
        host.send(Proto.msg(Proto.UNDO_APPLIED, roomCode, ROLE_HOST, p));
    }

    // ---------------- 认输 / 数子 ----------------

    public void resign() {
        if (state != PLAYING) return;
        if (isHost()) {
            hostEndByResign(ROLE_HOST);
        } else {
            try {
                JSONObject p = new JSONObject();
                p.put("by", colorChar(myColor));
                sendToHost(Proto.msg(Proto.RESIGN, roomCode, ROLE_GUEST, p));
            } catch (Exception ignored) {
            }
        }
    }

    public void requestScore() {
        if (state != PLAYING) return;
        if (scorePending) {
            // 待确认阶段点「数子」等于房主确认
            if (isHost()) confirmScore();
            return;
        }
        if (session == null || !session.rules.supportsScore()) {
            notifyNotice("该棋种按规则自动判定胜负");
            return;
        }
        if (!isHost()) {
            notifyNotice("数子由房主结算");
            return;
        }
        hostFinishPayload(session.rules.scoreResult());
    }

    private void hostEndByResign(String resigner) {
        int resignSeat = ROLE_HOST.equals(resigner) ? hostColor : 3 - hostColor;
        hostFinishPayload(session.rules.resultByResign(resignSeat));
    }

    private void hostTimeout(int colorIndex) {
        int loser = colorIndex == 0 ? 1 : 2;
        hostFinishPayload(session.rules.resultByTimeout(loser));
    }

    private void hostFinishPayload(JSONObject res) {
        if (res == null) return;
        cancelGrace();
        stopLinkWatch();
        scorePending = false;
        pendingScore = null;
        // 断线判负时保留 linkDown，结果页顶部才会显示"连接中断"而不是"已连接"
        resultCode = res.optString("result", "");
        resultDetail = res.optString("detail", "对局结束");
        resultReason = res.optString("reason", "");
        state = FINISHED;
        stopTicker();
        JSONObject p = new JSONObject();
        try {
            p.put("game", session.gameId);
            p.put("moveNo", session.moveNo);
            p.put("komi", session.komi);
            java.util.Iterator<String> it = res.keys();
            while (it.hasNext()) {
                String k = it.next();
                p.put(k, res.get(k));
            }
        } catch (Exception ignored) {
        }
        host.send(Proto.msg(Proto.GAME_OVER, roomCode, ROLE_HOST, p));
        notifyState();
        notifyGameOver(resultCode, resultDetail);
    }

    // ---------------- 收消息 ----------------

    private final LanHost.Callback hostCallback = new LanHost.Callback() {
        @Override
        public void onMessage(JSONObject msg) {
            lastRecvAt = SystemClock.elapsedRealtime();
            try {
                onHostMessage(msg);
            } catch (Exception e) {
                notifyError("协议错误：" + e.getMessage());
            }
        }

        @Override
        public void onGuestChanged(boolean connected) {
            if (connected) {
                // 仅在 TCP 层接入，尚未握手：保持 HOSTING，等 hello 校验通过再进 LOBBY。
                Log.i(TAG, "客机 TCP 已接入，等待 hello");
            } else {
                if (state == PLAYING) {
                    onHostLinkLost("对手连接已断开");
                } else if (state == LOBBY) {
                    state = HOSTING;
                    opponentName = "对手";
                    Log.w(TAG, "客机断开，回到等待状态");
                    notifyState();
                } else if (state == HOSTING) {
                    notifyState();
                }
            }
        }

        @Override
        public void onError(String message) {
            Log.e(TAG, "主机错误：" + message);
            notifyError(message);
        }
    };

    private final LanClient.Callback clientCallback = new LanClient.Callback() {
        @Override
        public void onMessage(JSONObject msg) {
            lastRecvAt = SystemClock.elapsedRealtime();
            try {
                onGuestMessage(msg);
            } catch (Exception e) {
                Log.e(TAG, "处理房主消息失败", e);
                notifyError("协议错误：" + e.getMessage());
            }
        }

        @Override
        public void onClosed(String reason) {
            pendingMove = false;
            synchronized (joinLock) {
                if (!joinAck && !joinRejected) {
                    joinCloseReason = reason == null ? "连接已断开" : reason;
                    joinLock.notifyAll();
                }
            }
            Log.w(TAG, "与房主连接断开：" + reason + " state=" + state);
            if (state == PLAYING) {
                onClientLinkLost(reason == null || reason.isEmpty() ? "连接已断开" : reason);
            } else if (state == LOBBY) {
                notifyNotice("与房主的连接已断开：" + reason);
                notifyState();
            }
        }
    };

    private void onHostMessage(JSONObject m) throws Exception {
        String t = Proto.type(m);
        JSONObject p = Proto.p(m);
        switch (t) {
            case Proto.HELLO:
                onHello(p);
                break;
            case Proto.MOVE:
                onGuestMove(p);
                break;
            case Proto.UNDO_REQUEST:
                pendingUndoFrom = ROLE_GUEST;
                pendingUndoCount = p.optInt("count", 1);
                notifyUndo(opponentName);
                break;
            case Proto.UNDO_RESPONSE:
                if (flag(p, "accept", false)) hostUndo(pendingUndoCount);
                pendingUndoFrom = null;
                break;
            case Proto.RESIGN:
                hostEndByResign(ROLE_GUEST);
                break;
            case Proto.SYNC_REQUEST:
                host.send(Proto.msg(Proto.SNAPSHOT, roomCode, ROLE_HOST, session.snapshot()));
                break;
            case Proto.PING:
                replyPong(p);
                break;
            case Proto.BYE:
                notifyNotice("对方已离开房间");
                break;
            default:
                break;
        }
    }

    private void onGuestMessage(JSONObject m) throws Exception {
        String t = Proto.type(m);
        JSONObject p = Proto.p(m);
        switch (t) {
            case Proto.HELLO_ACK: {
                if (flag(p, "resumed", false)) {
                    // 断线重连成功：用房主快照对齐棋盘与时钟
                    hintsEnabled = flag(p, "hints", hintsEnabled);
                    renjuEnabled = flag(p, "renju", renjuEnabled);
                    if (session != null && session.rules instanceof GomokuRules)
                        ((GomokuRules) session.rules).setRenju(renjuEnabled);
                    scorePending = false;
                    pendingScore = null;
                    JSONObject snap = p.optJSONObject("snapshot");
                    if (session != null && snap != null) session.restore(snap);
                    JSONObject ck = p.optJSONObject("clock");
                    if (ck != null) {
                        guestClock = ck;
                        guestClockAt = SystemClock.elapsedRealtime();
                    }
                    clockFrozen = false;
                    linkDown = false;
                    state = PLAYING;
                    lastRecvAt = SystemClock.elapsedRealtime();
                    lastPingAt = 0;
                    synchronized (joinLock) {
                        joinAck = true;
                        joinLock.notifyAll();
                    }
                    Log.i(TAG, "重连成功 moveNo=" + (session == null ? -1 : session.moveNo));
                    notifyNotice("已重新连接，对局继续");
                    notifyState();
                    notifyMove();
                    notifyClock();
                    startLinkWatch();
                    return;
                }
                synchronized (joinLock) {
                    joinAck = true;
                    joinLock.notifyAll();
                }
                String game = p.optString("game", GameAdapter.GO);
                gameId = game;
                String gid = p.optString("gameId", "");
                session = new LanSession(roomCode, game, gid);
                session.size = p.optInt("size", session.rules.boardSize());
                session.komi = p.optDouble("komi", session.komi);
                myColor = session.rules.seatFromChar(p.optString("yourColor", "W"));
                opponentName = p.optString("opponentName", "对手");
                hintsEnabled = flag(p, "hints", true);
                renjuEnabled = flag(p, "renju", false);
                if (session.rules instanceof GomokuRules)
                    ((GomokuRules) session.rules).setRenju(renjuEnabled);
                roomCode = p.optString("room", roomCode);
                state = LOBBY;
                Log.i(TAG, "握手成功 game=" + game + " 执" + myColorName() + " 对手=" + opponentName);
                notifyState();
                break;
            }
            case Proto.HELLO_REJECT: {
                String msg = p.optString("message", "无法加入房间");
                String code = p.optString("code", "");
                if (!code.isEmpty()) msg = "[" + code + "] " + msg;
                synchronized (joinLock) {
                    joinRejected = true;
                    joinRejectMessage = msg;
                    joinRejectCode = code;
                    joinLock.notifyAll();
                }
                Log.w(TAG, "房主拒绝加入：" + msg);
                break;
            }
            case Proto.GAME_START: {
                String gid = p.optString("gameId", "");
                String game = p.optString("game", GameAdapter.GO);
                double komi = p.optDouble("komi", 7.5);
                if (session == null || !gid.equals(session.matchId)
                        || !game.equals(session.gameId)) {
                    session = new LanSession(roomCode, game, gid);
                }
                gameId = session.gameId;
                session.size = p.optInt("size", session.rules.boardSize());
                renjuEnabled = flag(p, "renju", false);
                if (session.rules instanceof GomokuRules)
                    ((GomokuRules) session.rules).setRenju(renjuEnabled);
                session.reset(komi);
                scorePending = false;
                pendingScore = null;
                // 房间规则以房主下发为准
                hintsEnabled = flag(p, "hints", hintsEnabled);
                JSONObject tc = p.optJSONObject("timeControl");
                if (tc != null) {
                    mainSec = tc.optInt("mainSec", 0);
                    byoYomiSec = tc.optInt("byoYomiSec", byoYomiSec);
                    periods = tc.optInt("periods", periods);
                } else {
                    mainSec = 0;
                }
                state = PLAYING;
                Log.i(TAG, "开局 game=" + game + " 我执" + myColorName()
                        + " 计时=" + (mainSec > 0 ? mainSec + "s" : "关闭"));
                if (mainSec > 0) startTicker(); else stopTicker();
                lastRecvAt = SystemClock.elapsedRealtime();
                lastPingAt = 0;
                startLinkWatch();
                notifyState();
                notifyGameStart();
                break;
            }
            case Proto.MOVE_ACK:
                pendingMove = false;
                applyAck(p);
                break;
            case Proto.MOVE_REJECT:
                pendingMove = false;
                JSONObject snap = p.optJSONObject("snapshot");
                if (snap != null && session != null) session.restore(snap);
                notifyNotice(p.optString("message", "落子被拒绝"));
                notifyMove();
                notifyState();
                break;
            case Proto.SNAPSHOT:
                if (session != null) session.restore(p);
                notifyMove();
                break;
            case Proto.UNDO_REQUEST:
                pendingUndoFrom = ROLE_HOST;
                pendingUndoCount = p.optInt("count", 1);
                notifyUndo(opponentName);
                break;
            case Proto.UNDO_RESPONSE:
                pendingUndoFrom = null;
                if (!flag(p, "accept", false)) notifyNotice("房主拒绝悔棋");
                break;
            case Proto.UNDO_APPLIED:
                pendingUndoFrom = null;
                // 悔棋撤销数子确认（房主已重新结算）
                scorePending = false;
                if (session != null) {
                    JSONObject s2 = p.optJSONObject("snapshot");
                    if (s2 != null) session.restore(s2);
                }
                notifyMove();
                break;
            case Proto.CLOCK:
                guestClock = p;
                guestClockAt = SystemClock.elapsedRealtime();
                clockFrozen = false;
                notifyClock();
                break;
            case Proto.SCORE_PENDING:
                resultCode = p.optString("result", "");
                resultDetail = p.optString("detail", "双方各停一手");
                resultReason = p.optString("reason", "");
                scorePending = true;
                notifyNotice("双方各停一手，等待房主确认数子");
                notifyMove();
                notifyState();
                break;
            case Proto.GAME_OVER:
                scorePending = false;
                pendingScore = null;
                resultCode = p.optString("result", "");
                resultDetail = p.optString("detail", "对局结束");
                resultReason = p.optString("reason", "");
                state = FINISHED;
                linkDown = false;
                stopLinkWatch();
                stopTicker();
                notifyState();
                notifyGameOver(resultCode, resultDetail);
                break;
            case Proto.PING:
                replyPong(p);
                break;
            case Proto.BYE:
                notifyNotice("房主已离开房间");
                break;
            default:
                break;
        }
    }

    private void onHello(JSONObject p) throws Exception {
        Log.i(TAG, "收到 hello 房间=" + p.optString("roomCode", "") + " 昵称="
                + p.optString("deviceName", "") + " 棋种=" + p.optString("game", "-")
                + " 版本=" + p.optInt("protocolVersion", 0));
        if (!roomCode.equalsIgnoreCase(p.optString("roomCode", ""))) {
            sendHelloReject("E_ROOM_CODE", "房间码不对（本房间 " + roomCode + "）");
            return;
        }
        if (p.optInt("protocolVersion", 0) != Proto.VERSION) {
            sendHelloReject("E_VERSION", "版本不一致，请升级 App");
            return;
        }
        String guestGame = p.optString("game", "");
        if (!guestGame.isEmpty() && !guestGame.equals(session.gameId)) {
            sendHelloReject(Proto.E_GAME_MISMATCH,
                    "房主这局是" + session.rules.label() + "，请重新选择房间");
            return;
        }
        if (flag(p, "resume", false) && state != PLAYING) {
            sendHelloReject("E_NO_GAME", "这局已结束，无法恢复");
            return;
        }
        if (state == PLAYING) {
            // 断线重连：局中客机带着 matchId/手数回来，恢复会话而不是新建
            boolean resume = flag(p, "resume", false);
            String gid = p.optString("gameId", "");
            if (resume && session != null && gid.equals(session.matchId)) {
                host.markHandshaked();
                cancelGrace();
                linkDown = false;
                lastRecvAt = SystemClock.elapsedRealtime();
                lastPingAt = 0;
                if (clock != null) clock.resume();
                opponentName = safe(p.optString("deviceName", opponentName), "对手");
                JSONObject ack = new JSONObject();
                ack.put("ok", true);
                ack.put("resumed", true);
                ack.put("game", session.gameId);
                ack.put("gameId", session.matchId);
                ack.put("size", session.rules.boardSize());
                ack.put("komi", session.komi);
                ack.put("moveNo", session.moveNo);
                ack.put("yourColor", colorChar(3 - hostColor));
                ack.put("opponentName", localName);
                ack.put("hints", hintsEnabled ? 1 : 0);
                ack.put("renju", renjuEnabled ? 1 : 0);
                ack.put("snapshot", session.snapshot());
                if (clock != null && clock.active()) {
                    ack.put("clock", clock.snapshot(session.moveNo));
                }
                if (!host.send(Proto.msg(Proto.HELLO_ACK, roomCode, ROLE_HOST, ack))) {
                    notifyError("回送重连确认失败");
                    return;
                }
                Log.i(TAG, "对局已恢复 moveNo=" + session.moveNo
                        + " 计时=" + (clock != null && clock.active() ? "继续" : "关闭"));
                notifyNotice("对手已重新连接，计时继续");
                notifyState();
                notifyMove();
                notifyClock();
                return;
            }
            sendHelloReject("E_ALREADY_PLAYING", "房间已开局");
            return;
        }
        host.markHandshaked();
        opponentName = safe(p.optString("deviceName", "对手"), "对手");
        int guestColor = 3 - hostColor;
        JSONObject ack = new JSONObject();
        ack.put("ok", true);
        ack.put("game", session.gameId);
        ack.put("gameId", session.matchId);
        ack.put("size", session.rules.boardSize());
        ack.put("komi", session.komi);
        ack.put("seat", 1);
        ack.put("yourColor", colorChar(guestColor));
        ack.put("opponentName", localName);
        ack.put("hostPlatform", "android");
        ack.put("hints", hintsEnabled ? 1 : 0);
        ack.put("renju", renjuEnabled ? 1 : 0);
        ack.put("ruleset", session.rules instanceof GoRules ? "chinese-simple-ko"
                : session.rules instanceof GomokuRules ? (renjuEnabled ? "renju" : "freestyle")
                : session.rules instanceof BanqiRules ? "banqi" : "xiangqi");
        ack.put("serverTime", System.currentTimeMillis());
        if (!host.send(Proto.msg(Proto.HELLO_ACK, roomCode, ROLE_HOST, ack))) {
            notifyError("回送握手消息失败，请确认对手仍在同一网络");
            return;
        }
        state = LOBBY;
        Log.i(TAG, "握手完成 对手=" + opponentName + " 客执" + colorName(guestColor));
        notifyState();
    }

    private void sendHelloReject(String code, String message) {
        JSONObject p = new JSONObject();
        try {
            p.put("code", code);
            p.put("message", message);
        } catch (Exception ignored) {
        }
        Log.w(TAG, "拒绝加入 code=" + code + " " + message);
        host.send(Proto.msg(Proto.HELLO_REJECT, roomCode, ROLE_HOST, p));
        // 回包后再关闭，避免客机卡在“正在连接”
        main.postDelayed(() -> {
            if (host != null) host.closeGuest();
        }, 400);
    }

    private void onGuestMove(JSONObject p) {
        int guestColor = 3 - hostColor;
        GameAdapter.ApplyResult r = session.applyAuthority(guestColor, p,
                p.optInt("moveNo", 0), p.optString("hashBefore", ""));
        if (!r.ok) {
            JSONObject rej = new JSONObject();
            try {
                rej.put("moveNo", p.optInt("moveNo", 0));
                rej.put("code", r.code);
                rej.put("message", r.message);
                rej.put("authoritativeMoveNo", session.moveNo);
                rej.put("hash", session.hash());
                if ("E_HASH_MISMATCH".equals(r.code)) rej.put("snapshot", session.snapshot());
            } catch (Exception ignored) {
            }
            host.send(Proto.msg(Proto.MOVE_REJECT, roomCode, ROLE_HOST, rej));
            return;
        }
        if (clock != null && clock.active()) {
            clock.onMove(guestColor - 1);
            if (clock.timeoutColor() >= 0) {
                hostTimeout(clock.timeoutColor());
                return;
            }
        }
        sendAck(r);
        notifyMove();
        afterMove();
    }

    private void applyAck(JSONObject p) {
        if (session == null) return;
        boolean ok = session.applyAck(p, p.optInt("moveNo", 0), p.optString("hash", ""));
        JSONObject c = p.optJSONObject("clock");
        if (c != null) {
            guestClock = c;
            guestClockAt = SystemClock.elapsedRealtime();
        }
        if (!ok) {
            notifyNotice("状态不同步，正在向房主校准…");
            try {
                sendToHost(Proto.msg(Proto.SYNC_REQUEST, roomCode, ROLE_GUEST, new JSONObject()));
            } catch (Exception ignored) {
            }
        }
        notifyMove();
        notifyState();
    }

    private void replyPong(JSONObject ping) {
        JSONObject p = new JSONObject();
        try {
            p.put("t0", ping.optLong("t0", 0));
            p.put("serverTime", System.currentTimeMillis());
        } catch (Exception ignored) {
        }
        send(Proto.msg(Proto.PONG, roomCode, isHost() ? ROLE_HOST : ROLE_GUEST, p));
    }

    private void send(JSONObject msg) {
        if (isHost()) {
            if (host != null) host.send(msg);
        } else if (client != null) {
            sendToHost(msg);
        }
    }

    private boolean sendToHost(JSONObject msg) {
        LanClient c = client;
        if (c == null) {
            Log.w(TAG, "发送失败：尚未连上房主 type=" + Proto.type(msg));
            return false;
        }
        return c.send(msg);
    }

    // ---------------- 断线重连 ----------------

    /** 房主侧：客机断线，冻结时钟并开始 60 秒宽限期。 */
    private void onHostLinkLost(String reason) {
        if (state != PLAYING || linkDown) return;
        linkDown = true;
        lastPingAt = 0;
        if (clock != null) {
            clock.pauseAt(lastRecvAt > 0 ? lastRecvAt : SystemClock.elapsedRealtime());
            // 把"已暂停"的权威时钟立刻推给对方，双机冻结在同一数值上
            sendClock();
        }
        // 半开连接仍占着客机槽位，主动关闭，重连的新连接才进得来；
        // 延时 300ms 让上面那条"已暂停"的时钟快照有机会发出去。
        main.postDelayed(() -> {
            if (state == PLAYING && linkDown && host != null) host.closeGuest();
        }, 300);
        Log.w(TAG, "房主判定客机断线：" + reason);
        notifyNotice(reason + "，计时已暂停，等待重连（" + (RECONNECT_GRACE_MS / 1000) + " 秒）");
        notifyState();
        notifyClock();
        cancelGrace();
        graceTask = () -> {
            graceTask = null;
            if (state != PLAYING || !linkDown) return;
            Log.w(TAG, "重连宽限期结束，判客机断线负");
            hostFinishPayload(session.rules.resultByDisconnect(3 - hostColor));
        };
        main.postDelayed(graceTask, RECONNECT_GRACE_MS);
    }

    private void cancelGrace() {
        if (graceTask != null) {
            main.removeCallbacks(graceTask);
            graceTask = null;
        }
    }

    /** 客机侧：检测到房主失联，立即重连并保留棋盘。 */
    private void onClientLinkLost(String reason) {
        if (state != PLAYING || linkDown) return;
        linkDown = true;
        clockFrozen = true;
        lastPingAt = 0;
        Log.w(TAG, "与房主连接中断：" + reason);
        notifyNotice("连接中断，正在重连…（计时已暂停）");
        notifyState();
        notifyClock();
        startReconnect();
    }

    private void startReconnect() {
        final int epoch = ++linkEpoch;
        final String ip = lastHostIp;
        final int p = lastHostPort;
        final String gid = session == null ? "" : session.matchId;
        final String game = session == null ? gameId : session.gameId;
        if (ip.isEmpty() || p <= 0) {
            failReconnect("没有可用的房主地址");
            return;
        }
        new Thread(() -> {
            long deadline = SystemClock.elapsedRealtime() + RECONNECT_GRACE_MS;
            int attempt = 0;
            while (epoch == linkEpoch && state == PLAYING && linkDown
                    && SystemClock.elapsedRealtime() < deadline) {
                attempt++;
                resetJoinWait();
                LanClient c = new LanClient();
                client = c;
                try {
                    c.connect(ip, p, wrapFor(c));
                } catch (Exception e) {
                    if (client == c) client = null;
                    Log.w(TAG, "第 " + attempt + " 次重连失败：" + shortMsg(e));
                    sleepQuiet(1500);
                    continue;
                }
                JSONObject payload = new JSONObject();
                try {
                    payload.put("protocolVersion", Proto.VERSION);
                    payload.put("roomCode", roomCode);
                    payload.put("deviceName", localName);
                    payload.put("platform", "android");
                    payload.put("appVersion", BuildConfig.VERSION_NAME);
                    payload.put("clientId", clientId);
                    payload.put("game", game);
                    payload.put("resume", true);
                    payload.put("gameId", gid);
                    payload.put("moveNo", session == null ? 0 : session.moveNo);
                } catch (Exception ignored) {
                }
                if (!c.send(Proto.msg(Proto.HELLO, roomCode, ROLE_GUEST, payload))) {
                    if (client == c) client = null;
                    c.close();
                    sleepQuiet(1500);
                    continue;
                }
                Log.i(TAG, "第 " + attempt + " 次重连尝试已发出 hello");
                waitHello();
                if (!linkDown) {
                    Log.i(TAG, "重连成功（第 " + attempt + " 次）");
                    return;
                }
                if (joinRejected && "E_NO_GAME".equals(joinRejectCode)) {
                    Log.w(TAG, "房主已结束对局，无法恢复：" + joinRejectMessage);
                    main.post(() -> failReconnect("房主已结束这局对局"));
                    return;
                }
                if (joinRejected) Log.w(TAG, "房主暂未接受重连，稍后重试：" + joinRejectMessage);
                if (client == c) client = null;
                c.close();
                sleepQuiet(1500);
            }
            if (epoch == linkEpoch && state == PLAYING && linkDown) {
                main.post(() -> failReconnect("重连宽限期已过"));
            }
        }, "lan-reconnect").start();
    }

    /** 排除旧连接的迟到回调：只有当前连接的关闭/消息才生效。 */
    private LanClient.Callback wrapFor(final LanClient c) {
        return new LanClient.Callback() {
            @Override
            public void onMessage(JSONObject msg) {
                if (client == c) clientCallback.onMessage(msg);
            }

            @Override
            public void onClosed(String reason) {
                if (client == c) clientCallback.onClosed(reason);
                else Log.i(TAG, "忽略旧连接关闭：" + reason);
            }
        };
    }

    /** 宽限期内未能恢复：客机本地结束对局。 */
    private void failReconnect(String reason) {
        if (state != PLAYING || !linkDown) return;
        // 保留 linkDown：结果页顶部仍显示"连接中断"，避免和"已连接"自相矛盾
        state = FINISHED;
        stopLinkWatch();
        resultCode = "";
        resultReason = "disconnect";
        resultDetail = "与房主的连接未能恢复（" + reason + "），对局结束";
        Log.e(TAG, resultDetail);
        notifyState();
        notifyGameOver(resultCode, resultDetail);
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    /** 对局期间每 2 秒检查链路：房主发 PING 保活并检测静默，客机检测长时间无消息。 */
    private void startLinkWatch() {
        stopLinkWatch();
        linkWatch = new Runnable() {
            @Override
            public void run() {
                if (state != PLAYING) {
                    linkWatch = null;
                    return;
                }
                long now = SystemClock.elapsedRealtime();
                if (isHost()) {
                    if (!linkDown) {
                        if (now - lastPingAt >= LINK_PING_MS) {
                            lastPingAt = now;
                            sendLinkPing();
                        }
                        if (lastRecvAt > 0 && now - lastRecvAt > LINK_IDLE_MS) {
                            onHostLinkLost("对手长时间无响应");
                        }
                    }
                } else if (!linkDown && lastRecvAt > 0 && now - lastRecvAt > LINK_IDLE_MS + 2000) {
                    onClientLinkLost("与房主连接超时");
                }
                if (state == PLAYING && linkWatch == this) {
                    main.postDelayed(this, 2000);
                } else {
                    linkWatch = null;
                }
            }
        };
        main.postDelayed(linkWatch, 2000);
    }

    private void stopLinkWatch() {
        if (linkWatch != null) main.removeCallbacks(linkWatch);
        linkWatch = null;
    }

    private void sendLinkPing() {
        try {
            JSONObject p = new JSONObject();
            p.put("t0", System.currentTimeMillis());
            send(Proto.msg(Proto.PING, roomCode, isHost() ? ROLE_HOST : ROLE_GUEST, p));
        } catch (Exception ignored) {
        }
    }

    // ---------------- 时钟 ----------------

    private void startTicker() {
        stopTicker();
        ticker = new Runnable() {
            @Override
            public void run() {
                if (state == PLAYING) {
                    if (isHost() && clock != null && clock.active()) {
                        clock.tick();
                        if (clock.timeoutColor() >= 0) {
                            hostTimeout(clock.timeoutColor());
                            return;
                        }
                        long now = SystemClock.elapsedRealtime();
                        if (now - lastClockPush >= 1000) {
                            lastClockPush = now;
                            sendClock();
                        }
                    }
                    notifyClock();
                }
                main.postDelayed(this, 500);
            }
        };
        main.postDelayed(ticker, 500);
    }

    private void stopTicker() {
        if (ticker != null) main.removeCallbacks(ticker);
        ticker = null;
    }

    private void sendClock() {
        if (host != null && clock != null && clock.active()) {
            host.send(Proto.msg(Proto.CLOCK, roomCode, ROLE_HOST, clock.snapshot(session.moveNo)));
        }
    }

    /** 供 UI 显示：双方剩余时间标签。seat 为 1/2。 */
    public String clockLabel(int seat) {
        int idx = seat == 1 ? 0 : 1;
        String head = colorName(seat);
        if (mainSec <= 0) return head + " —";
        if (isHost() && clock != null) {
            long ms = clock.remain(idx);
            if (clock.inByoYomi(idx)) {
                return head + " 读秒 " + Math.max(0, ms / 1000) + "s ×"
                        + Math.max(0, clock.periodsLeft(idx));
            }
            return head + " " + fmt(ms);
        }
        if (guestClock != null) {
            boolean by = Proto.bool(guestClock, idx == 0 ? "blackByoYomi" : "whiteByoYomi", false);
            long ms = guestClock.optLong(idx == 0 ? "blackRemainMs" : "whiteRemainMs", 0);
            boolean running = Proto.bool(guestClock, "running", false);
            boolean paused = Proto.bool(guestClock, "paused", false);
            String turn = guestClock.optString("turn", "");
            boolean myTurn = turn.equals(idx == 0 ? "B" : "W");
            if (running && myTurn && !paused && !linkDown && !clockFrozen) {
                ms -= SystemClock.elapsedRealtime() - guestClockAt;
            }
            ms = Math.max(0, ms);
            if (by) {
                int per = guestClock.optInt(idx == 0 ? "blackPeriods" : "whitePeriods", 0);
                return head + " 读秒 " + (ms / 1000) + "s ×" + per;
            }
            return head + " " + fmt(ms);
        }
        return head + " —";
    }

    private String fmt(long ms) {
        long s = Math.max(0, ms) / 1000;
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    // ---------------- 结束 ----------------

    /** 退出流程互斥：避免 Activity 与大厅同时 stop 造成重复关闭。 */
    private volatile boolean stopping;
    /** 关闭 Socket/Nsd 等资源放到后台，避免在主线程做 IO。 */
    private final java.util.concurrent.ExecutorService closePool =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "lan-close");
                t.setDaemon(true);
                return t;
            });

    public void stop() {
        if (stopping) {
            Log.w(TAG, "退出对战：重复调用已忽略 state=" + state + " role=" + role);
            return;
        }
        stopping = true;
        Log.i(TAG, "退出对战：开始 state=" + state + " role=" + role + " room=" + roomCode);
        try {
            stopTicker();
            stopLinkWatch();
            cancelGrace();
            linkEpoch++;
            linkDown = false;
            attemptSeq.incrementAndGet();
            synchronized (joinLock) {
                joinCloseReason = "已停止";
                joinLock.notifyAll();
            }
            final LanHost h = host;
            final LanClient c = client;
            final LanDiscovery d = discovery;
            final LanRoomQuery.Responder qr = queryResponder;
            final String code = roomCode;
            host = null;
            client = null;
            discovery = null;
            queryResponder = null;
            instanceId = "";
            session = null;
            clock = null;
            guestClock = null;
            scorePending = false;
            pendingScore = null;
            clockFrozen = false;
            pendingMove = false;
            pendingUndoFrom = null;
            role = "";
            state = IDLE;
            roomCode = "";
            gameId = "";
            opponentName = "对手";
            resultCode = "";
            resultDetail = "";
            resultReason = "";
            lastError = "";
            hostAddress = "";
            port = 0;
            lastHostIp = "";
            lastHostPort = 0;
            lastRecvAt = 0;
            lastPingAt = 0;
            notifyState();
            // Socket close / NsdManager 注销都算 IO，统一在后台线程执行
            closePool.execute(() -> {
                try {
                    if (h != null) h.stop();
                    if (c != null) c.close();
                    if (qr != null) qr.stop();
                    if (d != null) {
                        Log.i(TAG, "退出对战：注销 mDNS room=" + code);
                        d.stop();
                    }
                    Log.i(TAG, "退出对战：完成");
                } catch (Throwable t) {
                    Log.e(TAG, "退出对战：关闭资源异常", t);
                } finally {
                    stopping = false;
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "退出对战：异常", t);
            stopping = false;
        }
    }

    // ---------------- 通知 ----------------

    /** 回调动作，便于统一做异常隔离。 */
    private interface Action {
        void run(Listener l);
    }

    /** 统一分发：单个监听者抛异常不能把主线程/进程带走。 */
    private void dispatch(String what, Action action) {
        for (Listener l : listeners) {
            try {
                action.run(l);
            } catch (Throwable t) {
                Log.e(TAG, "回调异常 what=" + what + " listener="
                        + l.getClass().getSimpleName(), t);
            }
        }
    }

    private void notifyState() {
        dispatch("state", Listener::onStateChanged);
    }

    private void notifyGameStart() {
        dispatch("gameStart", Listener::onGameStart);
    }

    private void notifyMove() {
        dispatch("move", Listener::onMove);
    }

    private void notifyClock() {
        dispatch("clock", Listener::onClock);
    }

    private void notifyGameOver(String result, String detail) {
        dispatch("gameOver", l -> l.onGameOver(result, detail));
    }

    private void notifyUndo(String who) {
        String w = who == null || who.isEmpty() ? "对方" : who;
        dispatch("undo", l -> l.onUndoRequest(w));
    }

    private void notifyNotice(String msg) {
        dispatch("notice", l -> l.onNotice(msg));
    }

    private void notifyError(String msg) {
        dispatch("error", l -> l.onError(msg));
    }

    private static String safe(String s, String def) {
        return s == null || s.trim().isEmpty() ? def : s.trim();
    }
}
