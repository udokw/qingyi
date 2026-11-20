package com.sijia.go;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.sijia.go.banqi.LanBanqiActivity;
import com.sijia.go.net.GameAdapter;
import com.sijia.go.net.LanDiscovery;
import com.sijia.go.net.LanUdpDiscovery;
import com.sijia.go.net.LanHub;
import com.sijia.go.net.LanRoomQuery;
import com.sijia.go.net.LanSelfTest;
import com.sijia.go.net.Proto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 联机大厅：创建房间(展示房间码/地址) 或 加入房间(mDNS 发现 + 手动 IP)。
 * 视觉沿用已选定的 B/C 双主题。
 */
public class LanLobbyActivity extends Activity {
    /** 从主页进入时指定默认棋种。 */
    public static final String EXTRA_GAME = "game";

    private UiTheme t;
    private LanHub hub;
    private LanDiscovery browser;
    /** mDNS 失效时的并行发现通道：UDP 广播查询 + 房主 room_announce。 */
    private LanUdpDiscovery udpDiscovery;

    private EditText etName, etCode, etAddress;
    private TextView segHost, segJoin;
    private TextView chipGameGo, chipGameXq, chipGameGmk, chipGameBq, chipB, chipW, chipT0, chipT1, chipT2, chipT3, chipHints, chipRenju;
    private View rowHints, rowRenju;
    private TextView tvRoomCode, tvHostAddress, tvHostMeta, tvGuestJoined, tvStatus, tvScanStatus;
    /** 发现诊断：默认收起，展开后每秒刷新一次计数。 */
    private TextView tvDiagToggle, tvDiagBody;
    private boolean diagOpen;
    private final Handler diagHandler = new Handler(Looper.getMainLooper());
    private final Runnable diagTick = new Runnable() {
        @Override
        public void run() {
            if (!diagOpen || isFinishing() || isDestroyed()) return;
            tvDiagBody.setText(com.sijia.go.net.LanDiag.get().report());
            diagHandler.postDelayed(this, 1000);
        }
    };
    private TextView lbTime;
    private View boxHosted, panelHost, panelJoin, btnStart;
    private LinearLayout listRooms;
    private ScrollView scrollBody;

    private int tab = 0;
    private String gameId = GameAdapter.GO;
    private int myColor = 1;
    private int mainSec = 1200;
    /** 房间规则：是否允许落点/可吃子提示（仅象棋生效），随 hello_ack / game_start 下发。 */
    private boolean hints = true;
    /** 房间规则：五子棋黑棋禁手，随 hello_ack / game_start 下发。 */
    private boolean renju = false;
    private boolean launched;
    private boolean guestRowShown;
    private final Map<String, LanDiscovery.Service> rooms = new LinkedHashMap<>();
    private LanDiscovery.Service picked;
    /** 探活执行器：所有网络探测都在后台串行执行，绝不阻塞主线程。 */
    private final java.util.concurrent.ExecutorService probePool =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread th = new Thread(r, "lan-room-probe");
                th.setDaemon(true);
                return th;
            });
    /** 正在探活的实例，避免同一房间被重复探测。 */
    private final java.util.Set<String> probing =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    /** 复查间隔 / 列表过期时间 / 新条目验证宽限。 */
    private static final long ROOM_PROBE_MS = 3500;
    private static final long ROOM_TTL_MS = 15000;
    private static final long ROOM_NEW_GRACE_MS = 6000;
    /** 定期探活：主动剔除杀进程/异常退出后 TTL 内残留的僵尸房间。 */
    private final Handler sweepHandler = new Handler(Looper.getMainLooper());
    private Runnable sweepTask;

    private final LanHub.Listener listener = new LanHub.Listener() {
        @Override
        public void onStateChanged() {
            android.util.Log.i("LanLobby", "onStateChanged state=" + hub.state()
                    + " host=" + hub.isHost() + " guest=" + hub.hasGuest());
            ui(LanLobbyActivity.this::render);
        }

        @Override
        public void onGameStart() {
            ui(LanLobbyActivity.this::launchGame);
        }

        @Override
        public void onError(String message) {
            ui(() -> {
                showStatus(message);
                Toast.makeText(LanLobbyActivity.this, message, Toast.LENGTH_LONG).show();
            });
        }

        @Override
        public void onNotice(String message) {
            ui(() -> {
                showStatus(message);
                Toast.makeText(LanLobbyActivity.this, message, Toast.LENGTH_SHORT).show();
            });
        }
    };

    private final LanDiscovery.Listener browseListener = new LanDiscovery.Listener() {
        @Override
        public void onFound(LanDiscovery.Service service) {
            ui(() -> {
                if (service.primaryPort() <= 0 || service.primaryHost().isEmpty()) {
                    com.sijia.go.net.LanDiag.get().mdnsDrop(
                            "mDNS 条目缺少地址/端口 code=" + service.code);
                    return;
                }
                long now = android.os.SystemClock.elapsedRealtime();
                String key = keyOf(service);
                LanDiscovery.Service old = rooms.get(key);
                if (old != null) {
                    // 同一实例被重复解析/被 UDP 通告刷新：只刷新时间，不重绘、不复探
                    old.lastSeen = now;
                    if (service.verified) {
                        old.verified = true;
                        old.lastOkAt = now;
                    }
                    return;
                }
                // 同一房间码只保留最新实例（房主重启会换 iid，旧条目必须让位）
                final String code = service.code;
                rooms.entrySet().removeIf(e -> e.getValue().code.equalsIgnoreCase(code)
                        && !keyOf(e.getValue()).equals(key));
                service.foundAt = now;
                rooms.put(key, service);
                renderRooms();
                if (service.verified) {
                    // UDP 通告/应答已经带回了房主真实地址，不需要再 TCP 探活
                    android.util.Log.i("LanLobby", "UDP 发现房间 " + code
                            + " → " + service.primaryHost() + ":" + service.primaryPort());
                    return;
                }
                android.util.Log.i("LanLobby", "发现房间 " + code + " iid=" + service.iid
                        + " pid=" + service.pid + " → 立即探活");
                probeAsync(service);
            });
        }
        @Override
        public void onLost(String serviceName) {
            ui(() -> {
                String key = null;
                for (Map.Entry<String, LanDiscovery.Service> e : rooms.entrySet()) {
                    if (e.getValue().name.equals(serviceName)) {
                        key = e.getKey();
                        break;
                    }
                }
                if (key == null) return;
                LanDiscovery.Service s = rooms.remove(key);
                if (picked != null && picked.name.equals(serviceName)) picked = null;
                renderRooms();
                android.util.Log.i("LanLobby", "mDNS 注销 " + (s == null ? serviceName : s.code));
            });
        }

        @Override
        public void onError(String message) {
            ui(() -> {
                showStatus(message);
                Toast.makeText(LanLobbyActivity.this, message, Toast.LENGTH_LONG).show();
            });
        }
    };

    /** 页面已销毁/正在结束时不再更新界面，避免回调打到已释放的 View 上。 */
    private void ui(Runnable action) {
        if (isFinishing() || isDestroyed()) return;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            try {
                action.run();
            } catch (Throwable t) {
                android.util.Log.e("LanLobbyActivity", "界面回调异常", t);
            }
        });
    }

    private static String keyOf(LanDiscovery.Service s) {
        return s.iid == null || s.iid.isEmpty() ? s.name : s.iid;
    }

    /** 按房间码找已发现的房间，用于「只填房号、不填地址」时自动补端口。 */
    private LanDiscovery.Service findByCode(String code) {
        if (code == null || code.isEmpty()) return null;
        for (LanDiscovery.Service s : rooms.values()) {
            if (s.code != null && s.code.equalsIgnoreCase(code)) return s;
        }
        return null;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_lan_lobby);
        // 每次进大厅重置诊断计数，避免把上一轮的收发数混进来
        com.sijia.go.net.LanDiag.get().reset();
        hub = LanHub.get();
        hub.addListener(listener);
        t = UiTheme.current(this);

        etName = findViewById(R.id.etName);
        etCode = findViewById(R.id.etCode);
        etAddress = findViewById(R.id.etAddress);
        segHost = findViewById(R.id.segHost);
        segJoin = findViewById(R.id.segJoin);
        chipGameGo = findViewById(R.id.chipGameGo);
        chipGameXq = findViewById(R.id.chipGameXq);
        chipGameGmk = findViewById(R.id.chipGameGmk);
        chipGameBq = findViewById(R.id.chipGameBq);
        chipB = findViewById(R.id.chipB);
        chipW = findViewById(R.id.chipW);
        chipT0 = findViewById(R.id.chipT0);
        chipT1 = findViewById(R.id.chipT1);
        chipT2 = findViewById(R.id.chipT2);
        chipT3 = findViewById(R.id.chipT3);
        chipHints = findViewById(R.id.chipHints);
        rowHints = findViewById(R.id.rowHints);
        chipRenju = findViewById(R.id.chipRenju);
        rowRenju = findViewById(R.id.rowRenju);
        tvRoomCode = findViewById(R.id.tvRoomCode);
        tvHostAddress = findViewById(R.id.tvHostAddress);
        tvHostMeta = findViewById(R.id.tvHostMeta);
        lbTime = findViewById(R.id.lbTime);
        tvGuestJoined = findViewById(R.id.tvGuestJoined);
        tvStatus = findViewById(R.id.tvStatus);
        tvScanStatus = findViewById(R.id.tvScanStatus);
        boxHosted = findViewById(R.id.boxHosted);
        panelHost = findViewById(R.id.panelHost);
        panelJoin = findViewById(R.id.panelJoin);
        btnStart = findViewById(R.id.btnStart);
        listRooms = findViewById(R.id.listRooms);
        scrollBody = findViewById(R.id.scrollBody);

        tvDiagToggle = findViewById(R.id.tvDiagToggle);
        tvDiagBody = findViewById(R.id.tvDiagBody);
        tvDiagToggle.setOnClickListener(v -> {
            diagOpen = !diagOpen;
            tvDiagBody.setVisibility(diagOpen ? View.VISIBLE : View.GONE);
            tvDiagToggle.setText(diagOpen ? "发现诊断 ▾" : "发现诊断 ▸");
            diagHandler.removeCallbacks(diagTick);
            if (diagOpen) {
                tvDiagBody.setText(com.sijia.go.net.LanDiag.get().report());
                diagHandler.postDelayed(diagTick, 1000);
                com.sijia.go.net.LanDiag.get().note("诊断面板展开");
            }
        });

        SharedPreferences sp = getSharedPreferences("lan", MODE_PRIVATE);
        etName.setText(sp.getString("name", ""));
        // 昵称改一次即落盘，重启后沿用，不必等到建房/加入才保存
        etName.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                String n = s.toString().trim();
                if (!n.isEmpty()) {
                    getSharedPreferences("lan", MODE_PRIVATE).edit().putString("name", n).apply();
                }
            }
        });
        // 房号只接受数字：粘贴的字母/空格即时过滤，最多 4 位
        etCode.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                String cleaned = normalizeCode(s.toString());
                if (!cleaned.equals(s.toString())) {
                    etCode.setText(cleaned);
                    etCode.setSelection(cleaned.length());
                }
            }
        });
        String extra = getIntent() == null ? "" : getIntent().getStringExtra(EXTRA_GAME);
        if (GameAdapter.XIANGQI.equals(extra) || GameAdapter.GO.equals(extra)
                || GameAdapter.GOMOKU.equals(extra) || GameAdapter.BANQI.equals(extra)) {
            gameId = extra;
        } else {
            String saved = sp.getString("game", "");
            gameId = GameAdapter.XIANGQI.equals(saved) ? GameAdapter.XIANGQI
                    : GameAdapter.GOMOKU.equals(saved) ? GameAdapter.GOMOKU
                    : GameAdapter.BANQI.equals(saved) ? GameAdapter.BANQI : GameAdapter.GO;
        }
        myColor = sp.getInt("color", 1) == 2 ? 2 : 1;
        mainSec = sp.getInt("main", 1200);
        hints = sp.getBoolean("hints", true);
        renju = sp.getBoolean("renju", false);

        segHost.setOnClickListener(v -> selectTab(0));
        segJoin.setOnClickListener(v -> selectTab(1));
        chipGameGo.setOnClickListener(v -> selectGame(GameAdapter.GO));
        chipGameXq.setOnClickListener(v -> selectGame(GameAdapter.XIANGQI));
        chipGameGmk.setOnClickListener(v -> selectGame(GameAdapter.GOMOKU));
        chipGameBq.setOnClickListener(v -> selectGame(GameAdapter.BANQI));
        chipB.setOnClickListener(v -> selectColor(1));
        chipW.setOnClickListener(v -> selectColor(2));
        chipT0.setOnClickListener(v -> selectTime(0));
        chipT1.setOnClickListener(v -> selectTime(600));
        chipT2.setOnClickListener(v -> selectTime(1200));
        chipT3.setOnClickListener(v -> selectTime(1800));
        chipHints.setOnClickListener(v -> toggleHints());
        chipRenju.setOnClickListener(v -> toggleRenju());
        findViewById(R.id.btnHost).setOnClickListener(v -> createRoom());
        findViewById(R.id.btnJoin).setOnClickListener(v -> join());
        btnStart.setOnClickListener(v -> hub.startGame());

        // 长按底部小字：本机回环自检（协议链路，不依赖 mDNS/局域网）
        findViewById(R.id.tvFoot).setOnLongClickListener(v -> {
            runSelfTest();
            return true;
        });

        applyTheme(t);
        selectTab(0);
        render();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        hub.removeListener(listener);
        diagHandler.removeCallbacks(diagTick);
        if (browser != null) browser.stop();
        stopUdpDiscovery();
        stopSweep();
        probing.clear();
        probePool.shutdownNow();
        if (isFinishing() && !launched) hub.stop();
    }

    @Override
    public void onBackPressed() {
        int s = hub.state();
        if (s == LanHub.HOSTING || s == LanHub.LOBBY || s == LanHub.CONNECTING) {
            new AlertDialog.Builder(this)
                    .setMessage(s == LanHub.CONNECTING ? "正在连接，离开将中断。" : "离开后房间将关闭。")
                    .setNegativeButton("留下", null)
                    .setPositiveButton("离开", (d, w) -> {
                        hub.stop();
                        finish();
                    })
                    .show();
        } else {
            super.onBackPressed();
        }
    }

    // ---------------- 操作 ----------------

    private void selectTab(int idx) {
        if (idx == 1) {
            int s = hub.state();
            if (s == LanHub.HOSTING || s == LanHub.LOBBY) {
                Toast.makeText(this, "当前已建房，请先离开房间", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        tab = idx;
        panelHost.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        panelJoin.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);
        if (idx == 1) startBrowse();
        else {
            if (browser != null) browser.stopBrowse();
            stopUdpDiscovery();
            stopSweep();
        }
        styleSegments();
    }

    private void selectGame(String id) {
        if (id.equals(gameId)) return;
        gameId = id;
        // 切换棋种时把执子重置为先手，避免“执白/黑”含义混用
        myColor = 1;
        styleChips();
        updateTimeLabel();
    }

    private void selectColor(int c) {
        myColor = c;
        styleChips();
    }

    private void selectTime(int sec) {
        mainSec = sec;
        styleChips();
        updateTimeLabel();
    }

    private void toggleHints() {
        hints = !hints;
        styleChips();
        getSharedPreferences("lan", MODE_PRIVATE).edit().putBoolean("hints", hints).apply();
    }

    private void toggleRenju() {
        renju = !renju;
        styleChips();
        getSharedPreferences("lan", MODE_PRIVATE).edit().putBoolean("renju", renju).apply();
    }

    private void updateTimeLabel() {
        if (lbTime == null) return;
        lbTime.setText("用时 · " + timeText());
    }

    private String timeText() {
        return mainSec <= 0 ? "不计时" : (mainSec / 60) + " 分钟 · 读秒 30 秒 ×3";
    }

    private void createRoom() {
        String name = etName.getText().toString().trim();
        if (name.isEmpty()) name = "棋友";
        savePrefs(name);
        hub.hostRoom(this, gameId, name, myColor, mainSec, 30, 3,
                !GameAdapter.GO.equals(gameId) && hints,
                GameAdapter.GOMOKU.equals(gameId) && renju);
        if (hub.state() == LanHub.ERROR) {
            showStatus(hub.lastError());
            return;
        }
        showStatus(hub.gameLabel() + "房间已创建，等待对手加入");
        render();
    }

    /**
     * 加入房间：只填房号即可。
     * 顺序：已发现列表按房号匹配 → 手动地址（写了端口直接连）→ UDP 广播/定向按房号查询真实端口。
     * 不再假设房主端口落在某个区间。
     */
    private void join() {
        final String code = normalizeCode(etCode.getText().toString());
        if (code.length() != 4) {
            showStatus("请输入四位数字房号");
            return;
        }
        etCode.setText(code);
        if (picked != null && !picked.code.equalsIgnoreCase(code)) picked = null;

        final String rawName = etName.getText().toString().trim();
        final String name = rawName.isEmpty() ? "棋友" : rawName;
        final String addr = etAddress.getText().toString().trim();
        final String game = picked == null ? null : picked.game;

        java.util.List<String> list = new java.util.ArrayList<>();
        if (picked != null) {
            for (String c : candidatesOf(picked)) if (!list.contains(c)) list.add(c);
        }
        LanDiscovery.Service byCode = findByCode(code);
        if (byCode != null) {
            for (String c : candidatesOf(byCode)) if (!list.contains(c)) list.add(c);
        }
        if (!addr.isEmpty() && hasExplicitPort(addr)) {
            for (String c : LanHub.manualCandidates(addr)) if (!list.contains(c)) list.add(c);
        }
        final boolean bareIp = !addr.isEmpty() && !hasExplicitPort(addr);
        if (!bareIp && !list.isEmpty()) {
            connect(list, code, name, game);
            return;
        }

        showStatus("正在按房号 " + code + " 查找房间…");
        final String host = addr.isEmpty() ? null : hostOnly(addr);
        probePool.execute(() -> {
            final LanRoomQuery.Lookup q = LanRoomQuery.query(host, code, bareIp ? 1500 : 2000);
            final java.util.List<String> found = q.endpoints;
            final java.util.List<String> merged = new java.util.ArrayList<>(list);
            // 手动填了裸 IP：先试双端锁定的默认端口 41234，再试查询到的真实端口
            if (bareIp) merged.add(0, host + ":" + Proto.DEFAULT_PORT);
            for (String c : found) if (!merged.contains(c)) merged.add(c);
            ui(() -> {
                if (merged.isEmpty()) {
                    showStatus(failureText(code, q, host));
                    return;
                }
                connect(merged, code, name, game);
            });
        });
    }

    /** 按房号查询失败时，给出能定位断点的原因，而不是笼统的「连接失败」。 */
    private static String failureText(String code, LanRoomQuery.Lookup q, String host) {
        if (!q.socketOk) {
            return "广播发送失败：UDP socket 不可用，请检查网络权限";
        }
        if (q.sent == 0) {
            return "广播发送失败：没有可用的广播地址（请确认已连上 Wi-Fi 或热点）";
        }
        if (q.target != null && q.target.contains("无广播地址")) {
            return "广播发送失败：当前网络没有广播地址";
        }
        if (q.replies == 0) {
            return "已向 " + q.target + " 发出 " + q.sent + " 次查询，但没有房主应答"
                    + "（对方可能不在同一网段，或防火墙拦截了 UDP 41235）";
        }
        return "收到 " + q.replies + " 个应答但全部被丢弃：" +
                (q.lastDrop.isEmpty() ? "原因未知" : q.lastDrop) +
                "（房号 " + code + "）";
    }

    private void connect(java.util.List<String> candidates, String code, String name, String game) {
        if (candidates == null || candidates.isEmpty()) {
            showStatus("没有可用的房主地址");
            return;
        }
        savePrefs(name);
        showStatus("正在连接 " + candidates.get(0) + " …");
        hub.joinRoom(this, candidates.toArray(new String[0]), code, name, game);
    }

    /** 房号规范化：只保留数字并截到 4 位，避免字母/空格导致匹配失败。 */
    private static String normalizeCode(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(4);
        for (int i = 0; i < text.length() && sb.length() < 4; i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') sb.append(c);
        }
        return sb.toString();
    }

    private static boolean hasExplicitPort(String addr) {
        return addr != null && addr.indexOf(':') >= 0;
    }

    /** 去掉 addr 里的端口，只留主机名/IP（裸 IPv6 原样返回）。 */
    private static String hostOnly(String addr) {
        String v = addr == null ? "" : addr.trim();
        int cut = v.lastIndexOf(':');
        if (cut > 0 && v.indexOf(':') == cut) return v.substring(0, cut);
        return v;
    }

    /** TXT 声明的地址优先，SRV 解析结果作为回退候选。 */
    private static String[] candidatesOf(LanDiscovery.Service s) {
        String first = s.primaryHost() + ":" + s.primaryPort();
        if (s.hasFallback()) {
            String second = s.host + ":" + s.port;
            if (!second.equals(first)) return new String[]{first, second};
        }
        return new String[]{first};
    }

    private void savePrefs(String name) {
        getSharedPreferences("lan", MODE_PRIVATE).edit()
                .putString("name", name)
                .putString("game", gameId)
                .putInt("color", myColor)
                .putInt("main", mainSec)
                .putBoolean("hints", hints)
                .apply();
    }

    private void launchGame() {
        if (launched) return;
        launched = true;
        Class<?> target = GameAdapter.XIANGQI.equals(hub.gameId())
                ? LanXiangqiActivity.class
                : GameAdapter.GOMOKU.equals(hub.gameId())
                ? LanGomokuActivity.class
                : GameAdapter.BANQI.equals(hub.gameId())
                ? LanBanqiActivity.class : LanGameActivity.class;
        startActivity(new Intent(this, target));
        finish();
    }

    private void runSelfTest() {
        showStatus("本机协议自检中…");
        LanSelfTest.run((ok, log) -> ui(() -> {
            showStatus(ok ? "协议自检通过" : "协议自检失败");
            new AlertDialog.Builder(this)
                    .setTitle(ok ? "协议自检通过" : "协议自检失败")
                    .setMessage(log)
                    .setPositiveButton("知道了", null)
                    .show();
        }));
    }

    // ---------------- 渲染 ----------------

    private void render() {
        int s = hub.state();
        if (s == LanHub.ERROR) {
            showStatus(hub.lastError());
        } else if (s == LanHub.CONNECTING) {
            showStatus("正在连接房主…");
        } else if (s == LanHub.LOBBY && !hub.isHost()) {
            showStatus("已连接，等待房主开始对局");
        } else if (s == LanHub.LOBBY) {
            showStatus("对手已加入，可以开始对局");
        } else if (s == LanHub.PLAYING) {
            launchGame();
            return;
        } else if (s == LanHub.HOSTING) {
            showStatus("房间已创建，等待对手加入");
        }
        renderHosted();
    }

    private void renderHosted() {
        int s = hub.state();
        boolean hosting = hub.isHost() && (s == LanHub.HOSTING || s == LanHub.LOBBY || s == LanHub.PLAYING);
        boxHosted.setVisibility(hosting ? View.VISIBLE : View.GONE);
        if (!hosting) return;
        tvRoomCode.setText(hub.roomCode());
        tvHostAddress.setText(hub.hostAddress());
        tvHostMeta.setText(hub.gameLabel() + " · " + timeText()
                + " · 房主执" + hub.colorName(hub.myColor()));
        boolean guest = hub.hasGuest() && (s == LanHub.LOBBY || s == LanHub.PLAYING);
        tvGuestJoined.setVisibility(guest ? View.VISIBLE : View.GONE);
        tvGuestJoined.setText("对手已加入：" + hub.opponentName());
        btnStart.setVisibility(guest && s == LanHub.LOBBY ? View.VISIBLE : View.GONE);
        // 首次出现“对手已加入/开始对局”时自动滚到底部，避免按钮落在屏幕外。
        if (guest && !guestRowShown) {
            guestRowShown = true;
            if (scrollBody != null) scrollBody.post(() -> scrollBody.fullScroll(View.FOCUS_DOWN));
        } else if (!guest) {
            guestRowShown = false;
        }
    }

    private void showStatus(String text) {
        tvStatus.setText(text == null ? "" : text);
        tvStatus.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void renderRooms() {
        if (listRooms == null) return;
        listRooms.removeAllViews();
        float d = getResources().getDisplayMetrics().density;
        int i = 0;
        for (Map.Entry<String, LanDiscovery.Service> e : rooms.entrySet()) {
            final LanDiscovery.Service s = e.getValue();
            final String addr = s.primaryHost() + ":" + s.primaryPort();
            LinearLayout wrap = new LinearLayout(this);
            wrap.setOrientation(LinearLayout.VERTICAL);

            SpannableString ss = new SpannableString(
                    "房间 " + s.code + "  ·  " + s.gameLabel() + " · " + s.boardText()
                            + "\n" + addr + (s.verified ? "     加入 ›" : "     正在确认房主…"));
            int end1 = 3 + s.code.length();
            ss.setSpan(new ForegroundColorSpan(t.accent), 3, end1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            ss.setSpan(new StyleSpan(Typeface.BOLD), 3, end1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            int start2 = ss.toString().indexOf('\n') + 1;
            ss.setSpan(new ForegroundColorSpan(t.subColor), start2, start2 + addr.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            ss.setSpan(new ForegroundColorSpan(s.verified ? t.subColor : t.accent),
                    start2 + addr.length(), ss.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            TextView row = new TextView(this);
            row.setText(ss);
            row.setTextSize(14);
            row.setLineSpacing(6 * d, 1f);
            row.setPadding((int) (16 * d), (int) (14 * d), (int) (16 * d), (int) (14 * d));
            if (t.hairlineList) {
                row.setBackgroundColor(Color.TRANSPARENT);
            } else {
                row.setBackground(t.entryDrawable(i % 3));
            }
            row.setOnClickListener(v -> joinDiscovered(s));
            wrap.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            if (t.hairlineList) {
                View line = new View(this);
                line.setBackgroundColor(t.dividerColor);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, (int) (0.7f * d)));
                wrap.addView(line, lp);
            } else {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) row.getLayoutParams();
                lp.bottomMargin = (int) (9 * d);
                row.setLayoutParams(lp);
            }
            listRooms.addView(wrap);
            i++;
        }
        if (rooms.isEmpty()) {
            tvScanStatus.setText("正在搜索同一 Wi-Fi 下的房间…");
        } else {
            tvScanStatus.setText("找到 " + rooms.size() + " 个房间，点击即可加入");
        }
    }

    private void startBrowse() {
        if (browser == null) browser = new LanDiscovery();
        rooms.clear();
        renderRooms();
        browser.browse(this, browseListener);
        if (udpDiscovery == null) {
            udpDiscovery = new LanUdpDiscovery(new LanUdpDiscovery.Listener() {
                @Override public void onFound(LanDiscovery.Service service) {
                    browseListener.onFound(service);
                }
                @Override public void onLost(String serviceName) {
                    browseListener.onLost(serviceName);
                }
            }, gameId);
            com.sijia.go.net.LanDiag.get().note("启动 UDP 发现 game=" + gameId);
            udpDiscovery.start();
        }
        startSweep();
    }

    private void stopUdpDiscovery() {
        if (udpDiscovery != null) {
            udpDiscovery.stop();
            udpDiscovery = null;
        }
    }

    /** 周期复核：房主已退出但 mDNS 记录未过期时，列表里会留下点不进去的僵尸条目。 */
    private void startSweep() {
        stopSweep();
        sweepTask = () -> {
            sweepRooms();
            if (sweepTask != null) sweepHandler.postDelayed(sweepTask, 3000);
        };
        sweepHandler.postDelayed(sweepTask, 3000);
    }

    private void stopSweep() {
        if (sweepTask != null) sweepHandler.removeCallbacks(sweepTask);
        sweepTask = null;
    }

    private void sweepRooms() {
        if (isFinishing()) return;
        long now = android.os.SystemClock.elapsedRealtime();
        for (LanDiscovery.Service s : new ArrayList<>(rooms.values())) {
            long last = s.lastOkAt > 0 ? s.lastOkAt : s.foundAt;
            if (s.verified && now - s.lastOkAt > ROOM_TTL_MS) {
                removeRoom(keyOf(s), "房间 " + s.code + " 长时间无响应，已移除");
            } else if (!s.verified && now - s.foundAt > ROOM_NEW_GRACE_MS) {
                // 新条目给 6 秒验证窗口，超时仍未通过探活即移除
                removeRoom(keyOf(s), "房间 " + s.code + " 未能确认房主，已移除");
            } else if (now - last > ROOM_PROBE_MS) {
                probeAsync(s);
            }
        }
    }

    /** 立即移除并同步 UI；探活失败不再等下一轮。 */
    private void removeRoom(String key, String reason) {
        LanDiscovery.Service s = rooms.remove(key);
        if (s == null) return;
        if (picked != null && keyOf(picked).equals(key)) picked = null;
        renderRooms();
        showStatus(reason);
        android.util.Log.i("LanLobby", reason + " iid=" + s.iid + " pid=" + s.pid
                + " addr=" + s.primaryHost() + ":" + s.primaryPort());
    }

    private void probeAsync(final LanDiscovery.Service s) {
        final String key = keyOf(s);
        if (!probing.add(key)) return;
        final String host = s.primaryHost();
        final int port = s.primaryPort();
        probePool.execute(() -> {
            final int r = LanDiscovery.probe(host, port, s.code, 1200);
            probing.remove(key);
            ui(() -> onProbeResult(s, key, r));
        });
    }

    private void onProbeResult(LanDiscovery.Service s, String key, int result) {
        if (isFinishing() || rooms.get(key) != s) return;
        long now = android.os.SystemClock.elapsedRealtime();
        android.util.Log.i("LanLobby", "房间探活 " + s.code + " iid=" + s.iid + " result=" + result);
        if (result == LanDiscovery.ALIVE || result == LanDiscovery.UNKNOWN) {
            s.verified = true;
            s.lastOkAt = now;
            s.lastSeen = now;
            renderRooms();
            return;
        }
        if (result == LanDiscovery.STALE) {
            // 端口有人监听但回复的是别的房间：旧 mDNS 记录（或同端口新房间）
            String msg = "房间 " + s.code + " 已被房主的新房间取代，已移除";
            if (picked != null && keyOf(picked).equals(key)) {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            }
            removeRoom(key, msg);
            return;
        }
        // 连不上：已验证过的条目立即移除；刚发现还没验证的给一次复核机会
        if (s.verified) {
            String msg = "房间 " + s.code + " 已失效（房主已退出），已移除";
            if (picked != null && keyOf(picked).equals(key)) {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            }
            removeRoom(key, msg);
        } else {
            probePool.execute(() -> {
                try {
                    Thread.sleep(400);
                } catch (InterruptedException ignored) {
                }
                final int again = LanDiscovery.probe(s.primaryHost(), s.primaryPort(), s.code, 1500);
                ui(() -> {
                    if (isFinishing() || rooms.get(key) != s) return;
                    if (again == LanDiscovery.ALIVE || again == LanDiscovery.UNKNOWN) {
                        s.verified = true;
                        s.lastOkAt = android.os.SystemClock.elapsedRealtime();
                        renderRooms();
                    } else {
                        removeRoom(key, "房间 " + s.code + " 已失效（房主已退出），已移除");
                    }
                });
            });
        }
    }

    /** 点击附近房间：先探活并校验房间码，避免进入僵尸房间后莫名连接失败。 */
    private void joinDiscovered(final LanDiscovery.Service s) {
        showStatus("正在确认房间 " + s.code + " …");
        probePool.execute(() -> {
            int r = LanDiscovery.probe(s.primaryHost(), s.primaryPort(), s.code, 1500);
            if (r == LanDiscovery.DEAD) {
                r = LanDiscovery.probe(s.primaryHost(), s.primaryPort(), s.code, 2000);
            }
            final int res = r;
            ui(() -> {
                if (isFinishing()) return;
                if (res == LanDiscovery.ALIVE || res == LanDiscovery.UNKNOWN) {
                    s.verified = true;
                    s.lastOkAt = android.os.SystemClock.elapsedRealtime();
                    picked = s;
                    etCode.setText(s.code);
                    etAddress.setText(s.primaryHost() + ":" + s.primaryPort());
                    join();
                    return;
                }
                rooms.remove(keyOf(s));
                if (picked == s) picked = null;
                renderRooms();
                String msg = res == LanDiscovery.STALE
                        ? "房间 " + s.code + " 已被房主的新房间取代，请选择当前房间"
                        : "房间 " + s.code + " 已失效（房主已退出），请选择其他房间";
                showStatus(msg);
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            });
        });
    }

    // ---------------- 主题 ----------------

    private void applyTheme(UiTheme t) {
        float d = getResources().getDisplayMetrics().density;
        boolean hara = t.hairlineList;
        Typeface serif = Typeface.create("serif", Typeface.BOLD);

        getWindow().setStatusBarColor(t.windowBg);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        findViewById(R.id.root).setBackgroundColor(t.windowBg);

        findViewById(R.id.rowEyebrow).setVisibility(hara ? View.VISIBLE : View.GONE);
        TextView eyebrow = findViewById(R.id.tvEyebrow);
        eyebrow.setTextColor(t.subColor);
        TextView seal = findViewById(R.id.tvSealMini);
        GradientDrawable sg = new GradientDrawable();
        sg.setColor(t.accent);
        sg.setCornerRadius(2 * d);
        seal.setBackground(sg);

        TextView title = findViewById(R.id.tvTitle);
        title.setTextColor(t.titleColor);
        title.setTypeface(serif);
        title.setLetterSpacing(hara ? 0.08f : 0.02f);
        ((TextView) findViewById(R.id.tvSub)).setTextColor(t.subColor);
        ((TextView) findViewById(R.id.tvFoot)).setTextColor(t.footColor);

        GradientDrawable box = new GradientDrawable();
        box.setColor(Color.TRANSPARENT);
        box.setStroke(Math.max(1, (int) d), hara ? t.dividerColor : t.dlgLine);
        box.setCornerRadius((hara ? 2 : 22) * d);
        findViewById(R.id.segGroup).setBackground(box);

        int[] labels = {R.id.lbName, R.id.lbGame, R.id.lbColor, R.id.lbTime, R.id.lbHints, R.id.lbRoom,
                R.id.lbAddr, R.id.lbCode, R.id.lbAddr2, R.id.lbNearby};
        for (int id : labels) {
            TextView lb = findViewById(id);
            lb.setTextColor(t.subColor);
            lb.setLetterSpacing(0.12f);
        }

        for (EditText et : new EditText[]{etName, etCode, etAddress}) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(hara ? Color.TRANSPARENT : t.entryBg[0]);
            bg.setStroke(Math.max(1, (int) d), t.dlgLine);
            bg.setCornerRadius((hara ? 2 : 16) * d);
            et.setBackground(bg);
            et.setTextColor(t.titleColor);
            et.setHintTextColor(t.footColor);
        }

        tvRoomCode.setTextColor(t.accent);
        tvRoomCode.setTypeface(serif);
        tvHostAddress.setTextColor(t.titleColor);
        tvHostAddress.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        tvHostMeta.setTextColor(t.subColor);
        ((TextView) findViewById(R.id.tvHostHint)).setTextColor(t.subColor);
        ((TextView) findViewById(R.id.tvJoinHint)).setTextColor(t.subColor);
        tvScanStatus.setTextColor(t.subColor);
        tvGuestJoined.setTextColor(t.titleColor);
        tvStatus.setTextColor(t.subColor);
        findViewById(R.id.divHosted).setBackgroundColor(t.dlgLine);
        findViewById(R.id.divNearby).setBackgroundColor(t.dlgLine);

        stylePrimary(findViewById(R.id.btnHost), t, d);
        stylePrimary(findViewById(R.id.btnJoin), t, d);
        stylePrimary(btnStart, t, d);

        styleSegments();
        styleChips();
        updateTimeLabel();
        renderRooms();
    }

    private void styleSegments() {
        if (t == null) return;
        float d = getResources().getDisplayMetrics().density;
        Typeface serif = Typeface.create("serif", Typeface.BOLD);
        TextView[] segs = {segHost, segJoin};
        for (int i = 0; i < 2; i++) {
            boolean active = tab == i;
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(active ? t.primaryBg : Color.TRANSPARENT);
            bg.setCornerRadius((t.hairlineList ? 2 : 19) * d);
            segs[i].setBackground(bg);
            segs[i].setTextColor(active ? t.primaryText : t.subColor);
            segs[i].setTypeface(serif);
            segs[i].setLetterSpacing(0.08f);
        }
    }

    private void styleChips() {
        if (t == null) return;
        boolean go = GameAdapter.GO.equals(gameId);
        boolean xq = GameAdapter.XIANGQI.equals(gameId);
        boolean gmk = GameAdapter.GOMOKU.equals(gameId);
        boolean bq = GameAdapter.BANQI.equals(gameId);
        chipGameGo.setText("围棋");
        chipGameXq.setText("象棋");
        chipGameGmk.setText("五子棋");
        chipGameBq.setText("暗棋");
        chipB.setText(bq ? "先手" : (go || gmk ? "执黑" : "执红"));
        chipW.setText(bq ? "后手" : (go || gmk ? "执白" : "执黑"));
        styleChip(chipGameGo, go);
        styleChip(chipGameXq, xq);
        styleChip(chipGameGmk, gmk);
        styleChip(chipGameBq, bq);
        styleChip(chipB, myColor == 1);
        styleChip(chipW, myColor == 2);
        styleChip(chipT0, mainSec <= 0);
        styleChip(chipT1, mainSec == 600);
        styleChip(chipT2, mainSec == 1200);
        styleChip(chipT3, mainSec == 1800);
        // 围棋没有提示；象棋为吃子提示，五子棋为 AI 落点提示
        if (rowHints != null) rowHints.setVisibility(go ? View.GONE : View.VISIBLE);
        TextView lbHints = findViewById(R.id.lbHints);
        if (lbHints != null) lbHints.setText(gmk ? "允许 AI 提示" : "落点提示与可吃子提示");
        chipHints.setText(hints ? "开" : "关");
        styleChip(chipHints, hints);
        // 五子棋：黑棋禁手开关
        if (rowRenju != null) rowRenju.setVisibility(gmk ? View.VISIBLE : View.GONE);
        chipRenju.setText(renju ? "开" : "关");
        styleChip(chipRenju, renju);
    }

    private void styleChip(TextView v, boolean selected) {
        float d = getResources().getDisplayMetrics().density;
        GradientDrawable bg = new GradientDrawable();
        if (selected) {
            bg.setColor(t.primaryBg);
            bg.setCornerRadius((t.hairlineList ? 2 : 22) * d);
            v.setTextColor(t.primaryText);
        } else {
            bg.setColor(Color.TRANSPARENT);
            bg.setStroke(Math.max(1, (int) d), t.hairlineList ? t.dividerColor : t.dlgLine);
            bg.setCornerRadius((t.hairlineList ? 2 : 22) * d);
            v.setTextColor(t.subColor);
        }
        v.setBackground(bg);
    }

    private void stylePrimary(View v, UiTheme t, float d) {
        if (!(v instanceof Button)) return;
        Button b = (Button) v;
        if (t.hairlineList) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(t.primaryBg);
            g.setCornerRadius(2 * d);
            b.setBackground(g);
            b.setTextColor(t.primaryText);
        } else {
            b.setBackground(t.buttonDrawable(1));
            b.setTextColor(t.buttonTextColor(1));
        }
        b.setTypeface(Typeface.create("serif", Typeface.BOLD));
        b.setLetterSpacing(0.18f);
    }
}
