package com.sijia.go;

import android.app.AlertDialog;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 象棋对弈 / 本地双人 / 棋谱复盘.
 * 与围棋同样的双主题(粉彩纪念碑 / 墨白留白).
 * 引擎: Fairy-Stockfish(Elo 分级) / Pikafish(神经网络).
 */
public class XiangqiActivity extends ComponentActivity {
    public static final String EXTRA_MODE = "mode";       // ai / local / review
    public static final String EXTRA_COLOR = "color";     // XiangqiGame.RED / BLACK
    public static final String EXTRA_LEVEL = "level";
    public static final String EXTRA_ENGINE = "engine";   // XiangqiEngine.ENGINE_FAIRY / PIKAFISH
    public static final String EXTRA_RECORD = "record_path";

    private XiangqiGame game = new XiangqiGame();
    private XiangqiBoardView boardView;
    private TextView tvStatus, tvStatusL, tvSubStatus, tvEyebrow, tvMoveLine, tvMotto, tvSign;
    private UiTheme theme;
    private XiangqiEngine engine;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private String mode = "ai";
    private int playerColor = XiangqiGame.RED;
    private int level = 5;
    private int engineId = XiangqiEngine.ENGINE_FAIRY;
    private volatile boolean thinking = false;
    private boolean showHints = true;
    /** 对局是否已结束; 结束后返回键不再拦截 */
    private boolean over = false;
    private OnBackPressedCallback backCallback;
    private boolean exitDialogShowing = false;
    private SoundPool soundPool;
    private int sndMove, sndCheck;

    private int selX = -1, selY = -1;
    private final List<String> moveLog = new ArrayList<>();
    private String lastMoveText = "";

    // 复盘
    private String reviewTitle = "棋谱复盘";
    private String reviewBaseFen = null;
    private final List<String> reviewUci = new ArrayList<>();
    private int reviewPos = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_xiangqi);

        mode = getIntent().getStringExtra(EXTRA_MODE);
        if (mode == null) mode = "ai";
        playerColor = getIntent().getIntExtra(EXTRA_COLOR, XiangqiGame.RED);
        level = getIntent().getIntExtra(EXTRA_LEVEL, 5);
        engineId = getIntent().getIntExtra(EXTRA_ENGINE, XiangqiEngine.ENGINE_FAIRY);
        showHints = getSharedPreferences("ui", MODE_PRIVATE).getBoolean("xq_hints", true);
        initSounds();

        boardView = findViewById(R.id.boardView);
        tvStatus = findViewById(R.id.tvStatus);
        tvStatusL = findViewById(R.id.tvStatusL);
        tvSubStatus = findViewById(R.id.tvSubStatus);
        tvEyebrow = findViewById(R.id.tvEyebrow);
        tvMoveLine = findViewById(R.id.tvMoveLine);
        tvMotto = findViewById(R.id.tvMotto);
        tvSign = findViewById(R.id.tvSign);

        boardView.setOnPointTapListener(this::onTap);
        boardView.setOnPieceDragListener(this::onDragMove);
        boardView.setFlipBlackText(mode.equals("local"));
        applyTheme(UiTheme.current(this));

        // 系统返回键: 仅 AI 对战 / 本地双人且未终局时拦截弹确认, 防止手滑结束对局.
        // 用 OnBackPressedDispatcher 而非已废弃的 onBackPressed(), 手势/预测性返回同样生效.
        backCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() { confirmExit(); }
        };
        getOnBackPressedDispatcher().addCallback(this, backCallback);
        updateBackCallback();

        findViewById(R.id.btnUndo).setOnClickListener(v -> onUndo());
        findViewById(R.id.btnResign).setOnClickListener(v -> onResign());
        findViewById(R.id.btnDraw).setOnClickListener(v -> onThirdButton());
        findViewById(R.id.btnPrev).setOnClickListener(v -> reviewStep(-1));
        findViewById(R.id.btnNext).setOnClickListener(v -> reviewStep(1));

        if (mode.equals("review")) {
            findViewById(R.id.row1).setVisibility(View.GONE);
            findViewById(R.id.rowNav).setVisibility(View.VISIBLE);
            tvMoveLine.setVisibility(View.VISIBLE);
            loadRecord(getIntent().getStringExtra(EXTRA_RECORD));
        } else if (mode.equals("ai")) {
            startEngineGame();
        } else {
            refresh();
        }
    }

    // ---------------- 人机对弈 ----------------
    private void startEngineGame() {
        setStatus("引擎启动中…");
        worker.execute(() -> {
            try {
                engine = new XiangqiEngine();
                engine.start(this, engineId, level);
                ui.post(() -> {
                    refresh();
                    if (!game.isOver() && game.turn() != playerColor) engineMove();
                });
            } catch (Exception e) {
                android.util.Log.e("XiangqiEngine", "start failed", e);
                ui.post(() -> {
                    setStatus("象棋引擎不可用");
                    Toast.makeText(this, XiangqiEngine.engineName(engineId) + " 启动失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void onTap(int x, int y) {
        if (mode.equals("review") || thinking || game.isOver()) return;
        int p = game.pieceAt(x, y);
        int turn = game.turn();
        if (mode.equals("ai") && turn != playerColor) return;

        // 未选中时直接点对方棋子: 若只有一枚己方棋子能吃到, 直接吃
        if (selX < 0 && p != XiangqiGame.EMPTY && XiangqiGame.colorOf(p) != turn) {
            List<XiangqiGame.Move> caps = capturesTo(x, y, turn);
            if (caps.size() == 1) {
                XiangqiGame.Move c = caps.get(0);
                if (tryPlay(c.fx, c.fy, x, y)) return;
            } else if (caps.size() > 1) {
                Toast.makeText(this, "有 " + caps.size() + " 枚棋子可吃, 请先选中一枚", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        if (selX >= 0 && selY >= 0) {
            if (p != XiangqiGame.EMPTY && XiangqiGame.colorOf(p) == turn) {
                select(x, y);
                return;
            }
            if (tryPlay(selX, selY, x, y)) return;
            Toast.makeText(this, game.illegalReason(selX, selY, x, y), Toast.LENGTH_SHORT).show();
            select(-1, -1);
            refresh();
            return;
        }
        if (p != XiangqiGame.EMPTY && XiangqiGame.colorOf(p) == turn) select(x, y);
        else Toast.makeText(this, "先选中己方棋子", Toast.LENGTH_SHORT).show();
    }

    /** 当前轮到的一方所有能落到 (tx,ty) 的着法(用于点对方棋子直接吃) */
    private List<XiangqiGame.Move> capturesTo(int tx, int ty, int color) {
        List<XiangqiGame.Move> out = new ArrayList<>();
        for (int y = 0; y < XiangqiGame.ROWS; y++) for (int x = 0; x < XiangqiGame.COLS; x++) {
            int p = game.pieceAt(x, y);
            if (p == XiangqiGame.EMPTY || XiangqiGame.colorOf(p) != color) continue;
            for (XiangqiGame.Move m : game.legalMovesFrom(x, y))
                if (m.tx == tx && m.ty == ty) out.add(m);
        }
        return out;
    }

    /** 拖拽落子: 从己方棋子拖到目标点 */
    private void onDragMove(int fx, int fy, int tx, int ty) {
        if (mode.equals("review") || thinking || game.isOver()) return;
        int turn = game.turn();
        if (mode.equals("ai") && turn != playerColor) return;
        int p = game.pieceAt(fx, fy);
        if (p == XiangqiGame.EMPTY || XiangqiGame.colorOf(p) != turn) {
            onTap(tx, ty);
            return;
        }
        if (tryPlay(fx, fy, tx, ty)) return;
        select(fx, fy);
        Toast.makeText(this, game.illegalReason(fx, fy, tx, ty), Toast.LENGTH_SHORT).show();
    }

    private void select(int x, int y) {
        if (x < 0) {
            selX = selY = -1;
            boardView.setSelection(-1, -1);
            boardView.setHints(new ArrayList<>());
            return;
        }
        selX = x; selY = y;
        boardView.setSelection(x, y);
        boardView.setHints(showHints ? game.legalMovesFrom(x, y) : new ArrayList<>());
    }

    private boolean tryPlay(int fx, int fy, int tx, int ty) {
        if (!game.play(fx, fy, tx, ty)) return false;
        XiangqiGame.Move m = game.history().get(0);
        lastMoveText = game.notation(m);
        moveLog.add(lastMoveText);
        afterMoveSound();
        if (game.inCheck(game.turn()) && !game.isOver())
            Toast.makeText(this, "将军！", Toast.LENGTH_SHORT).show();
        select(-1, -1);
        refresh();
        if (game.isOver()) {
            showGameOver();
            return true;
        }
        if (mode.equals("ai") && game.turn() != playerColor) engineMove();
        return true;
    }

    private void engineMove() {
        if (thinking || game.isOver() || engine == null) return;
        thinking = true;
        setStatus("对方思考中…");
        String fen = game.fen();
        worker.execute(() -> {
            String uci = null;
            try { uci = engine.bestMove(fen); }
            catch (Exception e) { android.util.Log.e("XiangqiEngine", "bestmove failed", e); }
            final String mv = uci;
            ui.post(() -> {
                thinking = false;
                if (mv == null) {
                    refresh();
                    if (!game.isOver()) setStatus("对方无着可走");
                    return;
                }
                if (!game.playUci(mv)) {
                    android.util.Log.e("XiangqiEngine", "illegal engine move: " + mv);
                    refresh();
                    setStatus("引擎返回非法着法");
                    return;
                }
                XiangqiGame.Move m = game.history().get(0);
                lastMoveText = game.notation(m);
                moveLog.add(lastMoveText);
                afterMoveSound();
                refresh();
                if (game.isOver()) showGameOver();
            });
        });
    }

    // ---------------- 操作 ----------------
    private void onUndo() {
        if (thinking || mode.equals("review") || game.isOver()) return;
        int n;
        if (mode.equals("ai")) {
            n = 0;
            if (game.plies() > 0) { game.undo(); n++; }
            if (game.plies() > 0 && game.turn() != playerColor) { game.undo(); n++; }
        } else {
            n = game.undo() == null ? 0 : 1;
        }
        for (int i = 0; i < n && !moveLog.isEmpty(); i++) moveLog.remove(moveLog.size() - 1);
        lastMoveText = moveLog.isEmpty() ? "" : moveLog.get(moveLog.size() - 1);
        select(-1, -1);
        refresh();
    }

    private void onResign() {
        if (mode.equals("review") || thinking) return;
        int loser = mode.equals("ai") ? playerColor : game.turn();
        UiTheme t = theme != null ? theme : UiTheme.current(this);
        AlertDialog dlg = UiTheme.themedBuilder(this, t, "认 输")
                .setMessage("确认认输?")
                .setPositiveButton("认输", (d, w) -> { game.resign(loser); refresh(); showGameOver(); })
                .setNegativeButton("继续", null)
                .create();
        dlg.show();
        UiTheme.styleDialog(dlg, t, true);
    }

    private void onThirdButton() {
        if (mode.equals("review")) return;
        UiTheme t = theme != null ? theme : UiTheme.current(this);
        if (mode.equals("ai")) {
            AlertDialog dlg = UiTheme.themedBuilder(this, t, "重 开")
                    .setMessage("重新开始一局?")
                    .setPositiveButton("重开", (d, w) -> restart())
                    .setNegativeButton("取消", null)
                    .create();
            dlg.show();
            UiTheme.styleDialog(dlg, t, false);
            return;
        }
        AlertDialog dlg = UiTheme.themedBuilder(this, t, "和 棋")
                .setMessage("双方同意和棋?")
                .setPositiveButton("和棋", (d, w) -> { game.agreeDraw(); refresh(); showGameOver(); })
                .setNegativeButton("继续", null)
                .create();
        dlg.show();
        UiTheme.styleDialog(dlg, t, false);
    }

    private void restart() {
        game = new XiangqiGame();
        over = false;
        updateBackCallback();
        moveLog.clear();
        lastMoveText = "";
        select(-1, -1);
        refresh();
        if (mode.equals("ai") && playerColor == XiangqiGame.BLACK) engineMove();
    }

    // ---------------- 棋谱复盘 ----------------
    private void loadRecord(String path) {
        setStatus("棋谱读取中…");
        worker.execute(() -> {
            try {
                XiangqiPgn.Record rec = XiangqiPgn.load(new File(path));
                ui.post(() -> {
                    reviewTitle = rec.title == null || rec.title.isEmpty() ? "棋谱复盘" : rec.title;
                    reviewBaseFen = rec.baseFen;
                    reviewUci.clear();
                    // 只保留能合法走完的前缀, 遇到坏着法提前收尾
                    XiangqiGame sim = new XiangqiGame();
                    if (reviewBaseFen != null) sim.loadFen(reviewBaseFen);
                    for (String u : rec.moves) {
                        if (!sim.playUci(u)) break;
                        reviewUci.add(u);
                    }
                    reviewPos = 0;
                    game = new XiangqiGame();
                    if (reviewBaseFen != null) game.loadFen(reviewBaseFen);
                    select(-1, -1);
                    refresh();
                    String warn = rec.warning;
                    if (warn.isEmpty() && reviewUci.size() < rec.moves.size())
                        warn = "第 " + (reviewUci.size() + 1) + " 着无法识别, 其后着法已忽略";
                    if (!warn.isEmpty())
                        Toast.makeText(this, warn, Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                android.util.Log.e("XiangqiPgn", "load failed", e);
                ui.post(() -> Toast.makeText(this, "棋谱读取失败", Toast.LENGTH_LONG).show());
            }
        });
    }

    private void reviewStep(int d) {
        if (d > 0) {
            if (reviewPos >= reviewUci.size()) {
                Toast.makeText(this, "已到最后一着", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!game.playUci(reviewUci.get(reviewPos))) {
                Toast.makeText(this, "第 " + (reviewPos + 1) + " 着无法识别", Toast.LENGTH_SHORT).show();
                return;
            }
            reviewPos++;
        } else {
            if (reviewPos <= 0) {
                Toast.makeText(this, "已到棋谱开头", Toast.LENGTH_SHORT).show();
                return;
            }
            game.undo();
            reviewPos--;
        }
        if (!game.isOver() && game.plies() > 0) {
            XiangqiGame.Move m = game.history().get(0);
            lastMoveText = game.notation(m);
        } else {
            lastMoveText = "";
        }
        refresh();
    }

    // ---------------- 界面 ----------------
    private void applyTheme(UiTheme t) {
        theme = t;
        float d = getResources().getDisplayMetrics().density;
        boolean hara = t.id == UiTheme.HARA;
        android.graphics.Typeface serif = android.graphics.Typeface.create("serif", android.graphics.Typeface.BOLD);

        getWindow().setStatusBarColor(t.windowBg);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        getWindow().getDecorView().setBackgroundColor(t.windowBg);

        tvStatus.setTextColor(t.titleColor);
        tvStatusL.setTextColor(t.titleColor);
        tvStatusL.setTypeface(serif);
        tvMoveLine.setTextColor(t.subColor);
        findViewById(R.id.rowEyebrow).setVisibility(hara ? View.VISIBLE : View.GONE);
        findViewById(R.id.tvSign).setVisibility(hara ? View.VISIBLE : View.GONE);
        findViewById(R.id.tvMotto).setVisibility(hara ? View.GONE : View.VISIBLE);
        tvSign.setText(((mode.equals("ai") ? XiangqiEngine.engineName(engineId).toUpperCase() + " · " : "") + "清 弈"));
        tvMotto.setTextColor(t.footColor);
        tvEyebrow.setTextColor(t.subColor);
        tvSubStatus.setTextColor(t.subColor);
        tvSubStatus.setVisibility(hara ? View.GONE : View.VISIBLE);
        View divTop = findViewById(R.id.divTop);
        divTop.setVisibility(hara ? View.VISIBLE : View.GONE);
        divTop.setBackgroundColor(hara ? t.dividerColor : t.dlgLine);

        android.graphics.drawable.GradientDrawable sg = new android.graphics.drawable.GradientDrawable();
        sg.setColor(t.accent);
        sg.setCornerRadius(2 * d);
        findViewById(R.id.tvSealMini).setBackground(sg);

        int redPiece = hara ? 0xFFA93226 : 0xFFC0553F;
        int blackPiece = hara ? 0xFF1A1A1A : 0xFF3E3A36;
        boardView.setThemeColors(t.boardBg, t.boardLine, redPiece, blackPiece, t.marker);
        // 棋盘与容器同宽: root 左右 padding 已归零, match_parent 直接铺满, 无需负边距(会被 clipToPadding 裁掉)

        styleButton(t, R.id.btnUndo, "悔棋", "UNDO", 0);
        styleButton(t, R.id.btnResign, "认输", "RESIGN", 2);
        styleButton(t, R.id.btnDraw, mode.equals("ai") ? "重开" : "求和", mode.equals("ai") ? "RESTART" : "DRAW", 0);
        styleButton(t, R.id.btnPrev, "◀ 上一步", "PREV", 0);
        styleButton(t, R.id.btnNext, "下一步 ▶", "NEXT", 1);

        int gridVis = hara && !mode.equals("review") ? View.VISIBLE : View.GONE;
        int[] gridIds = {R.id.gridTop, R.id.gridBottom, R.id.vG1, R.id.vG2};
        for (int id : gridIds) {
            findViewById(id).setVisibility(gridVis);
            findViewById(id).setBackgroundColor(t.dividerColor);
        }
        findViewById(R.id.vG3).setVisibility(mode.equals("review") && hara ? View.VISIBLE : View.GONE);
        findViewById(R.id.vG3).setBackgroundColor(t.dividerColor);

        if (hara) {
            tvStatusL.setVisibility(View.VISIBLE);
            tvStatus.setGravity(android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
            tvStatus.setTextSize(17);
            tvStatus.setTypeface(android.graphics.Typeface.create("serif", android.graphics.Typeface.NORMAL));
        } else {
            tvStatusL.setVisibility(View.GONE);
            tvStatus.setGravity(android.view.Gravity.CENTER);
            tvStatus.setTextSize(19);
            tvStatus.setTypeface(serif);
        }
    }

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

    private String leftText() {
        if (mode.equals("ai")) return playerColor == XiangqiGame.RED ? "我执红" : "我执黑";
        if (mode.equals("local")) return "本地双人";
        return "复 盘";
    }

    private CharSequence statusText() {
        boolean hara = theme != null && theme.id == UiTheme.HARA;
        if (mode.equals("review")) {
            return reviewTitle + "    第 " + reviewPos + " / " + reviewUci.size() + " 着";
        }
        String turn = game.turn() == XiangqiGame.RED ? "● 红方" : "● 黑方";
        String s = "轮到 " + turn + "行棋";
        if (game.inCheck(game.turn())) s += " · 将军！";
        if (hara) {
            android.text.SpannableString ss = new android.text.SpannableString(s);
            int i = s.indexOf(turn) + 2;
            ss.setSpan(new android.text.style.ForegroundColorSpan(theme.accent), i, i + 2, 0);
            return ss;
        }
        if (mode.equals("ai")) return (playerColor == XiangqiGame.RED ? "我执红  " : "我执黑  ") + s;
        return "本地双人  " + s;
    }

    private void refresh() {
        boardView.setBoard(game.board());
        List<XiangqiGame.Move> hs = game.history();
        if (!hs.isEmpty()) {
            XiangqiGame.Move m = hs.get(0);
            boardView.setLastMove(m.fx, m.fy, m.tx, m.ty);
        } else {
            boardView.setLastMove(-1, -1, -1, -1);
        }
        setStatus(statusText());
        tvStatusL.setText(leftText());

        if (mode.equals("review")) {
            tvSubStatus.setText("9 × 10 · 棋谱复盘");
            tvMoveLine.setText(reviewPos == 0 ? "棋谱开头" :
                    "第 " + reviewPos + " 着 · " + lastMoveText);
            tvEyebrow.setText(reviewPos == 0 ? "象棋 · 复 盘 · 开 头"
                    : "象棋 · 复 盘 · 第 " + reviewPos + " 着");
        } else {
            tvSubStatus.setText("9 × 10 · " + (mode.equals("ai")
                    ? (engineId == XiangqiEngine.ENGINE_FAIRY
                        ? "Elo " + XiangqiEngine.eloForLevel(level)
                        : "等级 " + level)
                    : "本地双人"));
            StringBuilder sb = new StringBuilder();
            int from = Math.max(0, moveLog.size() - 8);
            for (int i = from; i < moveLog.size(); i++) {
                if (i > from) sb.append("  ");
                if (i % 2 == 0) sb.append(i / 2 + 1).append(". ");
                sb.append(moveLog.get(i));
            }
            tvMoveLine.setText(sb.length() == 0 ? "楚河汉界 · 静待落子" : sb.toString());
            int n = game.plies();
            tvEyebrow.setText("象棋 · 第 " + cnNum(Math.max(n, 1)) + " 着");
        }
        if (!thinking) {
            // 保证状态行显示当前行棋方
        }
    }

    private void setStatus(CharSequence s) { tvStatus.setText(s); }

    private void showGameOver() {
        over = true;
        updateBackCallback();
        UiTheme t = theme != null ? theme : UiTheme.current(this);
        String title = game.result() == XiangqiGame.RESULT_DRAW ? "和 棋"
                : (game.result() == XiangqiGame.RED ? "红方胜" : "黑方胜");
        AlertDialog dlg = UiTheme.themedBuilder(this, t, title)
                .setMessage(game.endReason())
                .setPositiveButton("返回主页", (d, w) -> finish())
                .setNegativeButton("再看棋盘", null)
                .create();
        dlg.show();
        UiTheme.styleDialog(dlg, t, false);
    }

    /** 返回键确认: 只在 AI 对战/本地双人且未终局时启用; 复盘与终局后走默认返回 */
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

    private static String cnNum(int n) {
        String[] d = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
        if (n <= 10) return n == 10 ? "十" : d[n];
        if (n < 20) return "十" + d[n - 10];
        if (n < 100) return d[n / 10] + "十" + (n % 10 == 0 ? "" : d[n % 10]);
        return d[n / 100] + "百" + (n % 100 == 0 ? "" : (n % 100 < 10 ? "零" + d[n % 10] : cnNum(n % 100)));
    }

    private void initSounds() {
        soundPool = new SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        sndMove = soundPool.load(this, R.raw.xq_move, 1);
        sndCheck = soundPool.load(this, R.raw.xq_check, 1);
    }

    /** 落子声; 若这一手形成将军, 稍后叠加一声更明显的提示音 */
    private void afterMoveSound() {
        if (soundPool == null) return;
        soundPool.play(sndMove, 0.75f, 0.75f, 1, 0, 1f);
        if (game.inCheck(game.turn())) {
            ui.postDelayed(() -> {
                if (soundPool != null) soundPool.play(sndCheck, 0.85f, 0.85f, 2, 0, 1f);
            }, 130);
        }
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
