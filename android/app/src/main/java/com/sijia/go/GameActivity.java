package com.sijia.go;

import android.app.AlertDialog;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GameActivity extends ComponentActivity {
    public static final String EXTRA_MODE = "mode";       // gnu / local / review
    public static final String EXTRA_ENGINE = "engine";   // GtpEngine.ENGINE_GNU / ENGINE_PACHI
    public static final String EXTRA_COLOR = "color";     // GoGame.BLACK / WHITE (gnu 模式玩家执子)
    public static final String EXTRA_LEVEL = "level";
    public static final String EXTRA_HANDICAP = "handicap";
    public static final String EXTRA_SGF = "sgf_path";

    private GoGame game = new GoGame();
    private GoBoardView boardView;
    private TextView tvStatus, tvComment, tvSubStatus, tvEyebrow;
    private UiTheme theme;
    private GtpEngine engine;
    private ExecutorService worker = Executors.newSingleThreadExecutor();
    private Handler ui = new Handler(Looper.getMainLooper());

    private String mode = "gnu";
    private int engineId = GtpEngine.ENGINE_GNU;
    private int playerColor = GoGame.BLACK;
    private int level = 10, handicap = 0;
    private volatile boolean thinking = false;
    private SoundPool soundPool;
    private int sndStone;
    private boolean soundOn = true;
    /** 对局是否已结束(认输/数子); 结束后返回键不再拦截 */
    private boolean over = false;
    private OnBackPressedCallback backCallback;
    private boolean exitDialogShowing = false;

    // 打谱
    private Sgf.Game sgf;
    private int reviewPos = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_game);

        mode = getIntent().getStringExtra(EXTRA_MODE);
        if (mode == null) mode = "gnu";
        engineId = getIntent().getIntExtra(EXTRA_ENGINE, GtpEngine.ENGINE_GNU);
        playerColor = getIntent().getIntExtra(EXTRA_COLOR, GoGame.BLACK);
        level = getIntent().getIntExtra(EXTRA_LEVEL, 10);
        handicap = getIntent().getIntExtra(EXTRA_HANDICAP, 0);
        soundOn = getSharedPreferences("ui", MODE_PRIVATE).getBoolean("go_sound", true);
        initSound();

        boardView = findViewById(R.id.boardView);
        tvStatus = findViewById(R.id.tvStatus);
        tvComment = findViewById(R.id.tvComment);
        tvSubStatus = findViewById(R.id.tvSubStatus);
        tvEyebrow = findViewById(R.id.tvEyebrow);

        boardView.setOnBoardTapListener(this::onTap);

        Button btnUndo = findViewById(R.id.btnUndo);
        Button btnPass = findViewById(R.id.btnPass);
        Button btnEstimate = findViewById(R.id.btnEstimate);
        Button btnScore = findViewById(R.id.btnScore);
        Button btnResign = findViewById(R.id.btnResign);
        Button btnSave = findViewById(R.id.btnSave);
        Button btnPrev = findViewById(R.id.btnPrev);
        Button btnNext = findViewById(R.id.btnNext);
        applyTheme(UiTheme.current(this));

        // 系统返回键: 仅 AI 对战 / 本地双人且未终局时拦截弹确认, 防止手滑结束对局.
        // 用 OnBackPressedDispatcher 而非已废弃的 onBackPressed(), 手势/预测性返回同样生效.
        backCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() { confirmExit(); }
        };
        getOnBackPressedDispatcher().addCallback(this, backCallback);
        updateBackCallback();

        btnUndo.setOnClickListener(v -> onUndo());
        btnPass.setOnClickListener(v -> onPass());
        btnEstimate.setOnClickListener(v -> onEstimate());
        btnScore.setOnClickListener(v -> onScore());
        btnResign.setOnClickListener(v -> onResign());
        btnSave.setOnClickListener(v -> onSave());

        if (mode.equals("review")) {
            btnUndo.setVisibility(View.GONE);
            btnPass.setVisibility(View.GONE);
            btnEstimate.setVisibility(View.GONE);
            btnScore.setVisibility(View.GONE);
            btnResign.setVisibility(View.GONE);
            btnSave.setVisibility(View.GONE);
            btnPrev.setVisibility(View.VISIBLE);
            btnNext.setVisibility(View.VISIBLE);
            tvComment.setVisibility(View.VISIBLE);
            btnPrev.setOnClickListener(v -> reviewStep(-1));
            btnNext.setOnClickListener(v -> reviewStep(1));
            loadSgf(getIntent().getStringExtra(EXTRA_SGF));
        } else if (mode.equals("gnu")) {
            startEngineGame();
        } else {
            refresh(statusText());
        }
    }

    // ---------------- 人机对弈 ----------------
    private void startEngineGame() {
        setStatus("引擎启动中…");
        worker.execute(() -> {
            try {
                engine = new GtpEngine();
                engine.start(this, engineId, level);
                engine.boardsize(19);
                engine.komi(game.komi());
                engine.level(level);
                if (handicap > 0) {
                    String v = engine.fixedHandicap(handicap);
                    List<int[]> pts = game.parseVertices(v);
                    game.placeHandicap(pts);
                }
                ui.post(() -> {
                    refresh(statusText());
                    // 玩家执白(让子局白先)或执白时引擎先行
                    if (playerColor == GoGame.WHITE) engineMove();
                });
            } catch (Exception e) {
                android.util.Log.e("GtpEngine", "engine start failed", e);
                ui.post(() -> {
                    setStatus("引擎启动失败");
                    Toast.makeText(this, GtpEngine.engineName(engineId) + " 启动失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void onTap(int x, int y) {
        if (mode.equals("review") || thinking) return;
        if (mode.equals("gnu")) {
            if (game.turn() != playerColor) return;
            GoGame.Move m = game.play(x, y);
            if (m == null) {
                Toast.makeText(this, "此处不能落子", Toast.LENGTH_SHORT).show();
                return;
            }
            playStone();
            refresh(statusText());
            worker.execute(() -> {
                try {
                    engine.play(playerColor == GoGame.BLACK, game.toGtp(x, y));
                    ui.post(this::engineMove);
                } catch (Exception e) {
                    android.util.Log.e("GtpEngine", "play failed", e);
                    ui.post(() -> setStatus("引擎错误: " + e.getClass().getSimpleName() + " " + e.getMessage()));
                }
            });
        } else {
            GoGame.Move m = game.play(x, y);
            if (m == null) {
                Toast.makeText(this, "此处不能落子", Toast.LENGTH_SHORT).show();
                return;
            }
            playStone();
            refresh(statusText());
        }
    }

    private void engineMove() {
        if (thinking) return;
        thinking = true;
        setStatus("对方思考中…");
        worker.execute(() -> {
            try {
                String v = engine.genmove(playerColor == GoGame.BLACK ? false : true);
                int[] p = game.fromGtp(v);
                ui.post(() -> {
                    thinking = false;
                    if (p == null) {  // PASS 或 RESIGN
                        if (v.equalsIgnoreCase("RESIGN")) {
                            gameOver((playerColor == GoGame.BLACK ? "黑" : "白") + "中盘胜(对方认输)");
                        } else {
                            game.pass();
                            refresh(statusText() + " · 对方停一手");
                            if (lastTwoPass()) onScore();
                        }
                    } else {
                        game.play(p[0], p[1]);
                        playStone();
                        refresh(statusText());
                    }
                });
            } catch (Exception e) {
                android.util.Log.e("GtpEngine", "genmove failed", e);
                ui.post(() -> { thinking = false; setStatus("引擎错误: " + e.getClass().getSimpleName() + " " + e.getMessage()); });
            }
        });
    }

    private boolean lastTwoPass() {
        List<GoGame.Move> h = game.history();
        return h.size() >= 2 && h.get(0).x < 0 && h.get(1).x < 0;
    }

    // ---------------- 操作 ----------------
    private void onUndo() {
        if (thinking) return;
        if (mode.equals("local")) {
            if (game.undo() != null) refresh(statusText());
            return;
        }
        // 人机: 撤销引擎+玩家两手
        worker.execute(() -> {
            try {
                int n = 0;
                if (game.canUndo()) { engine.undo(); game.undo(); n++; }
                if (game.canUndo() && game.turn() != playerColor) { engine.undo(); game.undo(); n++; }
                int fn = n;
                ui.post(() -> { if (fn > 0) refresh(statusText()); else refresh("无法悔棋"); });
            } catch (Exception e) { ui.post(() -> setStatus("悔棋失败")); }
        });
    }

    private void onPass() {
        if (thinking || mode.equals("review")) return;
        game.pass();
        refresh(statusText());
        if (mode.equals("gnu")) {
            worker.execute(() -> {
                try {
                    engine.play(playerColor == GoGame.BLACK, "PASS");
                    if (lastTwoPass()) ui.post(this::onScore);
                    else ui.post(this::engineMove);
                } catch (Exception e) {
                    android.util.Log.e("GtpEngine", "pass failed", e);
                    ui.post(() -> setStatus("引擎错误: " + e.getClass().getSimpleName() + " " + e.getMessage()));
                }
            });
        }
    }

    private void onEstimate() {
        if (thinking) return;
        if (mode.equals("gnu") && engine != null) {
            setStatus("形势判断中…");
            worker.execute(() -> {
                String est = "?";
                try { est = engine.estimateScore(); } catch (Exception ignored) {}
                String f = formatScore(est);
                ui.post(() -> { setStatus("形势: " + f); showTerritory(); });
            });
        } else {
            float[] s = game.chineseScore();
            setStatus(String.format("形势: 黑%.0f 白%.0f(子空皆地,不含贴目)", s[0], s[1]));
            showTerritory();
        }
    }

    /** 把引擎的 "W+13.3 (upper bound...)" 格式化为 "白领先约 13.3 目" */
    private static String formatScore(String raw) {
        if (raw == null) return "?";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("([BW])\\+\\s*([0-9.]+)").matcher(raw);
        if (m.find()) {
            String who = m.group(1).equals("B") ? "黑" : "白";
            return who + "领先约 " + m.group(2) + " 目";
        }
        if (raw.startsWith("0")) return "势均力敌";
        return raw;
    }

    /** 主题: 窗口/状态行(分栏)/眉题印章/副标题/棋盘内缩/底座/小字/按钮网格/签字 */
    private void applyTheme(UiTheme t) {
        this.theme = t;
        getWindow().setStatusBarColor(t.windowBg);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        getWindow().getDecorView().setBackgroundColor(t.windowBg);
        tvStatus.setTextColor(t.titleColor);
        tvComment.setTextColor(t.subColor);
        float d = getResources().getDisplayMetrics().density;
        boolean hara = t.id == UiTheme.HARA;
        android.graphics.Typeface serif = android.graphics.Typeface.create("serif", android.graphics.Typeface.BOLD);

        // 墨白: 眉题行 + 右上小印章 + 发丝线 + 底部签字; 粉彩: 副标 + 底座下小字
        findViewById(R.id.rowEyebrow).setVisibility(hara ? View.VISIBLE : View.GONE);
        View divTop = findViewById(R.id.divTop);
        divTop.setVisibility(View.VISIBLE);
        divTop.setBackgroundColor(hara ? t.dividerColor : t.dlgLine);
        findViewById(R.id.tvSign).setVisibility(hara ? View.VISIBLE : View.GONE);
        ((TextView) findViewById(R.id.tvSign)).setTextColor(t.footColor);
        if (mode.equals("gnu")) ((TextView) findViewById(R.id.tvSign))
                .setText(GtpEngine.engineName(engineId).toUpperCase() + " · 清 弈");
        findViewById(R.id.tvMotto).setVisibility(hara ? View.GONE : View.VISIBLE);
        ((TextView) findViewById(R.id.tvMotto)).setTextColor(t.footColor);
        tvSubStatus.setVisibility(hara ? View.GONE : View.VISIBLE);
        tvSubStatus.setTextColor(t.subColor);
        tvEyebrow.setTextColor(t.subColor);
        android.graphics.drawable.GradientDrawable sg = new android.graphics.drawable.GradientDrawable();
        sg.setColor(t.accent);
        sg.setCornerRadius(2 * d);
        ((TextView) findViewById(R.id.tvSealMini)).setBackground(sg);

        TextView tvL = findViewById(R.id.tvStatusL);
        if (hara) {
            tvL.setVisibility(View.VISIBLE);
            tvL.setText(leftText());
            tvL.setTextColor(t.titleColor);
            tvL.setTypeface(serif);
            tvStatus.setGravity(android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
            tvStatus.setTextSize(17);
            tvStatus.setTypeface(android.graphics.Typeface.create("serif", android.graphics.Typeface.NORMAL));
        } else {
            tvL.setVisibility(View.GONE);
            tvStatus.setGravity(android.view.Gravity.CENTER);
            tvStatus.setTextSize(19);
            tvStatus.setTypeface(serif);
        }
        if (mode.equals("gnu")) {
            tvSubStatus.setText(game.size() + " 路 · 等级 " + level + (handicap > 0 ? " · 让 " + handicap + " 子" : ""));
        } else if (mode.equals("local")) {
            tvSubStatus.setText(game.size() + " 路 · 本地双人");
        } else {
            tvSubStatus.setText(game.size() + " 路 · 棋谱打谱");
        }

        // 棋盘与容器同宽: root 左右 padding 已归零, match_parent 直接铺满, 无需负边距(会被 clipToPadding 裁掉)
        boardView.setThemeColors(t.boardBg, t.boardLine, t.marker);
        android.view.View ped = findViewById(R.id.pedestal);
        if (t.id == UiTheme.MONUMENT) {
            android.graphics.drawable.GradientDrawable pg = new android.graphics.drawable.GradientDrawable();
            pg.setColor(android.graphics.Color.parseColor("#D8C9B8"));
            pg.setCornerRadius(5 * d);
            ped.setBackground(pg);
            ped.setVisibility(View.VISIBLE);
        } else {
            ped.setVisibility(View.GONE);
        }

        styleButton(t, R.id.btnUndo, "悔棋", "UNDO", t.id == UiTheme.MONUMENT ? 1 : 0);
        styleButton(t, R.id.btnPass, "停一手", "PASS", 0);
        styleButton(t, R.id.btnEstimate, "形势", "SCORE EST.", 0);
        styleButton(t, R.id.btnScore, "数子", "COUNT", 0);
        styleButton(t, R.id.btnResign, "认输", "RESIGN", 2);
        styleButton(t, R.id.btnSave, "存谱", "SAVE SGF", 0);
        styleButton(t, R.id.btnPrev, "◀ 上一手", "PREV", 0);
        styleButton(t, R.id.btnNext, "下一手 ▶", "NEXT", 1);

        // 墨白发丝网格: 上中下三条横线 + 行内竖线(打谱模式只留导航行的上下线)
        boolean review = mode.equals("review");
        int gridVis = (hara && !review) ? View.VISIBLE : View.GONE;
        int[] gridLines = {R.id.gridTop, R.id.gridMid, R.id.gridBottom,
                R.id.vG1, R.id.vG2, R.id.vG3, R.id.vG4};
        for (int id : gridLines) {
            View v = findViewById(id);
            v.setVisibility(gridVis);
            v.setBackgroundColor(t.dividerColor);
        }
        int navVis = (hara && review) ? View.VISIBLE : View.GONE;
        int[] navLines = {R.id.grid3Top, R.id.grid3Bottom, R.id.vG5};
        for (int id : navLines) {
            View v = findViewById(id);
            v.setVisibility(navVis);
            v.setBackgroundColor(t.dividerColor);
        }
        // 粉彩: 按钮行与 motto/棋盘拉开间距
        View row1 = findViewById(R.id.row1);
        android.widget.LinearLayout.LayoutParams rlp = (android.widget.LinearLayout.LayoutParams) row1.getLayoutParams();
        rlp.topMargin = (int) ((hara ? 0 : 40) * d);
        row1.setLayoutParams(rlp);
    }

    /** kind: 0 普通 / 1 主按钮 / 2 危险; 墨白=透明+双语, 线由容器画; 粉彩=pill+宽松间距 */
    private void styleButton(UiTheme t, int id, String zh, String en, int kind) {
        Button b = findViewById(id);
        b.setBackground(t.buttonDrawable(kind));
        float d = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout.LayoutParams lp = (android.widget.LinearLayout.LayoutParams) b.getLayoutParams();
        if (t.hairlineList) {
            lp.setMargins(0, 0, 0, 0);
            android.text.SpannableString s = new android.text.SpannableString(zh + "\n" + en);
            s.setSpan(new android.text.style.RelativeSizeSpan(0.62f), zh.length() + 1, s.length(), 0);
            s.setSpan(new android.text.style.ForegroundColorSpan(t.subColor), zh.length() + 1, s.length(), 0);
            b.setText(s);
            b.setPadding(0, (int) (12 * d), 0, (int) (12 * d));
        } else {
            int m = (int) (6 * d);
            lp.setMargins(m, m, m, m);
            b.setText(zh);
            b.setPadding(0, (int) (10 * d), 0, (int) (10 * d));
        }
        b.setLayoutParams(lp);
        b.setTextColor(t.buttonTextColor(kind));
    }

    private void showTerritory() {
        int sz = game.size();
        int[][] t = new int[sz][sz];
        int[][] b = game.board();
        // 用本地数子结果近似展示地域
        float[] s = game.chineseScore();
        // 简单复算 owner
        java.util.Arrays.stream(t).forEach(r -> java.util.Arrays.fill(r, GoGame.EMPTY));
        boolean[][] vis = new boolean[sz][sz];
        for (int y = 0; y < sz; y++) for (int x = 0; x < sz; x++) {
            if (b[y][x] != GoGame.EMPTY || vis[y][x]) continue;
            java.util.List<int[]> region = new java.util.ArrayList<>();
            java.util.Set<Integer> adj = new java.util.HashSet<>();
            java.util.Deque<int[]> q = new java.util.ArrayDeque<>();
            q.add(new int[]{x, y}); vis[y][x] = true;
            while (!q.isEmpty()) {
                int[] p = q.poll(); region.add(p);
                int[][] D = {{1,0},{-1,0},{0,1},{0,-1}};
                for (int[] d : D) {
                    int nx = p[0]+d[0], ny = p[1]+d[1];
                    if (nx<0||nx>=sz||ny<0||ny>=sz) continue;
                    if (b[ny][nx] == GoGame.EMPTY) {
                        if (!vis[ny][nx]) { vis[ny][nx] = true; q.add(new int[]{nx,ny}); }
                    } else adj.add(b[ny][nx]);
                }
            }
            if (adj.size() == 1) {
                int c = adj.iterator().next();
                for (int[] p : region) t[p[1]][p[0]] = c;
            }
        }
        boardView.showTerritory(t);
        boardView.postDelayed(() -> boardView.clearTerritory(), 5000);
    }

    private void onScore() {
        // 不用引擎 final_score: GNU Go 中盘数子要做全盘死活分析, 实测手机上要 3 分钟以上
        // 本地中国规则数子(子空皆地)即时出结果
        float[] s = game.chineseScore();
        double w = s[1] + game.komi();
        double diff = Math.abs(s[0] - w);
        String res = s[0] > w ? "黑胜" : "白胜";
        gameOver(String.format("数子结果(子空皆地, 未提死子)\n黑 %.0f : 白 %.0f(含贴目 %.1f)\n%s %.1f 目",
                s[0], w, game.komi(), res, diff));
    }

    private void onResign() {
        if (mode.equals("review")) return;
        String winner = game.turn() == GoGame.BLACK ? "白" : "黑";
        UiTheme t = theme != null ? theme : UiTheme.current(this);
        AlertDialog dlg = UiTheme.themedBuilder(this, t, "认 输")
                .setMessage("确认认输?")
                .setPositiveButton("认输", (d, w) -> gameOver(winner + "中盘胜"))
                .setNegativeButton("继续", null)
                .create();
        dlg.show();
        UiTheme.styleDialog(dlg, t, true);
    }

    private void onSave() {
        if (mode.equals("review")) return;
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOCUMENTS), "清弈");
            File f = Sgf.saveToFile(game,
                    mode.equals("gnu") ? (playerColor == GoGame.BLACK ? "我" : GtpEngine.engineName(engineId)) : "黑方",
                    mode.equals("gnu") ? (playerColor == GoGame.BLACK ? GtpEngine.engineName(engineId) : "我") : "白方",
                    null, dir);
            Toast.makeText(this, "已保存: " + f.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void gameOver(String msg) {
        over = true;
        updateBackCallback();
        UiTheme t = theme != null ? theme : UiTheme.current(this);
        AlertDialog dlg = UiTheme.themedBuilder(this, t, "对局结束")
                .setMessage(msg)
                .setPositiveButton("返回主页", (d, w) -> finish())
                .setNegativeButton("再看棋盘", null)
                .create();
        dlg.show();
        UiTheme.styleDialog(dlg, t, false);
    }

    /** 返回键确认: 只在 AI 对战/本地双人且未终局时启用; 打谱与终局后走默认返回 */
    private void updateBackCallback() {
        if (backCallback != null) backCallback.setEnabled(!mode.equals("review") && !over);
    }

    private void confirmExit() {
        if (exitDialogShowing) return;
        UiTheme t = theme != null ? theme : UiTheme.current(this);
        AlertDialog dlg = UiTheme.themedBuilder(this, t, "结束对局")
                .setMessage("结束本局并返回主页?")
                .setPositiveButton("结束本局", (d, w) -> finish())
                .setNegativeButton("继续对局", null)
                .create();
        dlg.setOnDismissListener(d -> exitDialogShowing = false);
        exitDialogShowing = true;
        dlg.show();
        UiTheme.styleDialog(dlg, t, true);
    }

    // ---------------- 打谱 ----------------
    private void loadSgf(String path) {
        worker.execute(() -> {
            try {
                Sgf.Game loaded = Sgf.load(new File(path));
                ui.post(() -> {
                    sgf = loaded;
                    game = new GoGame(sgf.size);
                    game.setKomi(sgf.komi);
                    game.setup(sgf.setupBlack, sgf.setupWhite, sgf.setupEmpty, sgf.playerToMove);
                    boardView.setBoardSize(game.size());
                    reviewPos = 0;
                    tvComment.setText(rootComment());
                    refresh(null);
                    setStatus(recordTitle() + "    手数 0 / " + totalMoves());
                });
            } catch (Exception e) {
                android.util.Log.e("Sgf", "load failed", e);
                ui.post(() -> Toast.makeText(this, "棋谱读取失败", Toast.LENGTH_LONG).show());
            }
        });
    }

    private String recordTitle() {
        StringBuilder sb = new StringBuilder();
        if (!sgf.event.isEmpty()) sb.append(sgf.event).append("  ");
        if (!sgf.blackName.isEmpty() || !sgf.whiteName.isEmpty()) {
            sb.append(sgf.blackName.isEmpty() ? "黑方" : sgf.blackName);
            if (!sgf.blackRank.isEmpty()) sb.append(' ').append(sgf.blackRank);
            sb.append(" vs ");
            sb.append(sgf.whiteName.isEmpty() ? "白方" : sgf.whiteName);
            if (!sgf.whiteRank.isEmpty()) sb.append(' ').append(sgf.whiteRank);
        } else {
            sb.append("棋谱打谱");
        }
        if (!sgf.result.isEmpty()) sb.append("  [").append(sgf.result).append(']');
        return sb.toString().trim();
    }

    private String rootComment() {
        if (sgf == null || sgf.nodes.isEmpty()) return "";
        String c = sgf.nodes.get(0).comment;
        return c == null ? "" : c;
    }

    /** 已进行到的手数(不含纯注释/摆子节点) */
    private int reviewMoveNo() {
        if (sgf == null) return 0;
        int n = 0;
        for (int i = 0; i < reviewPos && i < sgf.nodes.size(); i++)
            if (sgf.nodes.get(i).color != 0) n++;
        return n;
    }

    private void reviewStep(int d) {
        if (sgf == null) return;
        if (d > 0 && reviewPos < sgf.nodes.size()) {
            Sgf.Node n = sgf.nodes.get(reviewPos++);
            if (n.color != 0) {
                if (n.x >= 0) game.play(n.x, n.y);
                else game.pass();
            }
        } else if (d < 0 && reviewPos > 0) {
            Sgf.Node n = sgf.nodes.get(--reviewPos);
            if (n.color != 0) game.undo();
        } else return;
        String c = reviewPos == 0 ? rootComment()
                : (reviewPos <= sgf.nodes.size() ? sgf.nodes.get(reviewPos - 1).comment : null);
        tvComment.setText(c == null ? "" : c);
        refresh(null);
        setStatus(recordTitle() + "    手数 " + reviewMoveNo() + " / " + totalMoves());
    }

    private int totalMoves() {
        int n = 0;
        for (Sgf.Node node : sgf.nodes) if (node.color != 0) n++;
        return n;
    }

    // ---------------- 界面辅助 ----------------
    /** 左栏静态文案(墨白): 我执黑 / 本地双人 / 打 谱 */
    private String leftText() {
        if (mode.equals("gnu")) return playerColor == GoGame.BLACK ? "● 我执黑" : "○ 我执白";
        if (mode.equals("local")) return "本地双人";
        return "打 谱";
    }

    private CharSequence statusText() {
        boolean hara = theme != null && theme.id == UiTheme.HARA;
        String turn = game.turn() == GoGame.BLACK ? "● 黑方" : "○ 白方";
        if (hara) {
            String s = "轮到 " + turn + "行棋";
            android.text.SpannableString ss = new android.text.SpannableString(s);
            int i = s.indexOf(turn) + 2;
            ss.setSpan(new android.text.style.ForegroundColorSpan(theme.accent), i, i + 2, 0);
            return ss;
        }
        if (mode.equals("gnu")) {
            return (playerColor == GoGame.BLACK ? "我执黑  " : "我执白  ") + turn + "行棋";
        }
        return "本地双人  " + turn + "行棋";
    }

    /** 中文小写数字(1-399), 眉题「第 X 手」用 */
    private static String cnNum(int n) {
        String[] d = {"零","一","二","三","四","五","六","七","八","九"};
        if (n <= 10) return n == 10 ? "十" : d[n];
        if (n < 20) return "十" + d[n - 10];
        if (n < 100) return d[n / 10] + "十" + (n % 10 == 0 ? "" : d[n % 10]);
        return d[n / 100] + "百" + (n % 100 == 0 ? "" : (n % 100 < 10 ? "零" + d[n % 10] : cnNum(n % 100)));
    }

    private void refresh(CharSequence status) {
        if (status != null) setStatus(status);
        int lastX = -1, lastY = -1;
        List<GoGame.Move> h = game.history();
        if (!h.isEmpty() && h.get(0).x >= 0) { lastX = h.get(0).x; lastY = h.get(0).y; }
        boardView.setBoard(game.board(), lastX, lastY);
        if (theme != null) {
            int n = mode.equals("review") ? reviewMoveNo() : h.size();
            if (theme.id == UiTheme.HARA) {
                tvEyebrow.setText(mode.equals("review") && n == 0 ? "棋谱 · 开 头"
                        : (mode.equals("review") ? "棋谱 · 第 " : "对局 · 第 ") + cnNum(Math.max(n, 1)) + " 手");
            } else {
                String sub = (n == 0 ? "未落子" : "第 " + n + " 手") + " · " + game.size() + " 路";
                if (mode.equals("gnu")) sub += " · 等级 " + level;
                else if (mode.equals("local")) sub += " · 本地双人";
                else sub += " · 打谱";
                tvSubStatus.setText(sub);
            }
        }
    }

    private void setStatus(CharSequence s) { tvStatus.setText(s); }

    private void initSound() {
        if (!soundOn) return;
        soundPool = new SoundPool.Builder()
                .setMaxStreams(1)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        sndStone = soundPool.load(this, R.raw.xq_move, 1);
    }

    /** 落子声(与象棋同款木石声) */
    private void playStone() {
        if (!soundOn || soundPool == null) return;
        soundPool.play(sndStone, 0.7f, 0.7f, 1, 0, 1f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onPause() {
        super.onPause();
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (engine != null) engine.stop();
        if (soundPool != null) { soundPool.release(); soundPool = null; }
        worker.shutdownNow();
    }
}
