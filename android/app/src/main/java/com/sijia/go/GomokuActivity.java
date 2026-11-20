package com.sijia.go;

import android.app.AlertDialog;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;

import java.io.File;

/**
 * 五子棋单机页：人机对弈（自研 AI）/ 本地双人。
 * 版式与围棋、象棋对局页一致：状态行 → 规则行 → 19 路棋盘 → 动作区 → 页脚。
 * 支持存谱 / 读谱（SGF GM[4]，逐手回放）与黑棋禁手开关（默认关闭）.
 */
public class GomokuActivity extends ComponentActivity {
    public static final String EXTRA_MODE = "mode";     // ai / local
    public static final String EXTRA_LEVEL = "level";   // 1..5
    public static final String EXTRA_COLOR = "color";   // GomokuGame.BLACK / WHITE
    public static final String EXTRA_HINTS = "hints";
    public static final String EXTRA_RENJU = "renju";

    private GomokuGame game = new GomokuGame();
    private GomokuBoardView boardView;
    private TextView tvStatus, tvStatusL, tvSubStatus, tvEyebrow;
    private UiTheme theme;

    private String mode = "ai";
    private int level = 3;
    private int playerColor = GomokuGame.BLACK;
    private boolean hintsEnabled = true;
    /** 黑棋禁手开关（默认关 = freestyle）. */
    private boolean renju = false;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile int aiGen = 0;
    private volatile boolean aiThinking = false;
    private boolean over = false;
    private boolean resultShowing = false;
    /** 终局原因（认输/超时等）；五连获胜与和棋由棋局本身判定，这里只补文案。 */
    private String endReason;
    /** 认输等非五连终局的 SGF 结果码（B+R / W+R），存谱时写入 RE. */
    private String endResult = "";
    private boolean exitDialogShowing = false;
    private OnBackPressedCallback backCallback;
    private boolean hintWorking = false;
    /** 读谱回放中. */
    private boolean replaying = false;
    private int replayGen = 0;
    private SoundPool soundPool;
    private int sndStone;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_gomoku);
        initSounds();

        mode = getIntent().getStringExtra(EXTRA_MODE);
        if (mode == null) mode = "ai";
        level = Math.max(1, Math.min(GomokuAi.MAX_LEVEL,
                getIntent().getIntExtra(EXTRA_LEVEL, 3)));
        playerColor = getIntent().getIntExtra(EXTRA_COLOR, GomokuGame.BLACK) == GomokuGame.WHITE
                ? GomokuGame.WHITE : GomokuGame.BLACK;
        hintsEnabled = getIntent().getBooleanExtra(EXTRA_HINTS, true);
        renju = getIntent().getBooleanExtra(EXTRA_RENJU, false);
        game.setRenju(renju);

        tvStatus = findViewById(R.id.tvStatus);
        tvStatusL = findViewById(R.id.tvStatusL);
        tvSubStatus = findViewById(R.id.tvSubStatus);
        tvEyebrow = findViewById(R.id.tvEyebrow);
        boardView = findViewById(R.id.boardView);
        boardView.setOnBoardTapListener(this::onTap);

        Button btnUndo = findViewById(R.id.btnUndo);
        Button btnHint = findViewById(R.id.btnHint);
        Button btnResign = findViewById(R.id.btnResign);
        Button btnNew = findViewById(R.id.btnNew);
        Button btnSave = findViewById(R.id.btnSave);
        Button btnLoad = findViewById(R.id.btnLoad);
        Button btnRule = findViewById(R.id.btnRule);

        backCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                confirmExit();
            }
        };
        getOnBackPressedDispatcher().addCallback(this, backCallback);

        btnUndo.setOnClickListener(v -> onUndo());
        btnHint.setOnClickListener(v -> onHint());
        btnResign.setOnClickListener(v -> onResign());
        btnNew.setOnClickListener(v -> startNewGame());
        btnSave.setOnClickListener(v -> onSave());
        btnLoad.setOnClickListener(v -> onLoad());
        btnRule.setOnClickListener(v -> toggleRenju());

        applyTheme(UiTheme.current(this));
        startNewGame();
    }

    private void startNewGame() {
        aiGen++;
        aiThinking = false;
        over = false;
        hintWorking = false;
        resultShowing = false;
        replayGen++;
        replaying = false;
        endReason = null;
        endResult = "";
        game.clear();
        game.setRenju(renju);
        boardView.clearHint();
        boardView.setGame(game);
        boardView.setInputEnabled(true);
        refreshStatus();
        updateBackCallback();
        if (isAiMode() && game.turn() != playerColor) aiMove();
    }

    private boolean isAiMode() {
        return "ai".equals(mode);
    }

    private void onTap(int x, int y) {
        if (over || aiThinking || replaying) return;
        if (isAiMode() && game.turn() != playerColor) return;
        int r = game.tryPlay(x, y);
        if (r == GomokuGame.PLAY_FORBIDDEN) {
            Toast.makeText(this, game.forbiddenText(x, y), Toast.LENGTH_SHORT).show();
            return;
        }
        if (r != GomokuGame.PLAY_OK) return;
        playStone();
        boardView.clearHint();
        afterMove();
    }

    private void afterMove() {
        boardView.setGame(game);
        refreshStatus();
        if (game.isOver()) {
            finishByWin();
            return;
        }
        if (game.isFull()) {
            over = true;
            boardView.setInputEnabled(false);
            updateBackCallback();
            setStatus("棋盘已满 · 和棋");
            showResult(false);
            return;
        }
        if (isAiMode() && game.turn() != playerColor) aiMove();
    }

    private void aiMove() {
        if (over) return;
        aiThinking = true;
        boardView.setInputEnabled(false);
        setStatus("AI 思考中…");
        final int gen = ++aiGen;
        final int[][] snap = game.copyBoard();
        final int aiColor = 3 - playerColor;
        final int lv = level;
        final GomokuAi.MoveFilter filter = (renju && aiColor == GomokuGame.BLACK)
                ? (x, y) -> !GomokuGame.isForbiddenPoint(snap, x, y) : null;
        new Thread(() -> {
            int[] mv = GomokuAi.choose(snap, aiColor, lv, filter);
            ui.post(() -> {
                if (isFinishing() || isDestroyed() || gen != aiGen || over) return;
                aiThinking = false;
                if (mv == null || !game.play(mv[0], mv[1])) {
                    aiThinking = false;
                    boardView.setInputEnabled(true);
                    refreshStatus();
                    return;
                }
                playStone();
                boardView.clearHint();
                boardView.setGame(game);
                boardView.setInputEnabled(true);
                refreshStatus();
                if (game.isOver()) finishByWin();
                else if (game.isFull()) {
                    over = true;
                    updateBackCallback();
                    setStatus("棋盘已满 · 和棋");
                    showResult(false);
                }
            });
        }, "gomoku-ai").start();
    }

    private void onUndo() {
        if (over || replaying || game.moveCount() == 0) return;
        // 取消尚在计算的 AI 手，避免悔棋后又被落回来
        aiGen++;
        aiThinking = false;
        hintWorking = false;
        if (isAiMode()) {
            if (game.canUndo() && lastColor() != playerColor) game.undo();
            if (game.canUndo()) game.undo();
        } else {
            game.undo();
        }
        boardView.clearHint();
        boardView.setGame(game);
        boardView.setInputEnabled(true);
        refreshStatus();
    }

    private int lastColor() {
        if (game.moveCount() == 0) return GomokuGame.EMPTY;
        java.util.List<int[]> h = game.history();
        return h.get(h.size() - 1)[0];
    }

    private void onHint() {
        if (over || aiThinking || hintWorking || replaying) return;
        if (isAiMode() && game.turn() != playerColor) return;
        hintWorking = true;
        setStatus("正在计算提示…");
        final int[][] snap = game.copyBoard();
        final int color = game.turn();
        final GomokuAi.MoveFilter filter = (renju && color == GomokuGame.BLACK)
                ? (x, y) -> !GomokuGame.isForbiddenPoint(snap, x, y) : null;
        new Thread(() -> {
            int[] mv = GomokuAi.choose(snap, color, Math.max(3, level), filter);
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                hintWorking = false;
                if (over) return;
                if (mv == null) {
                    Toast.makeText(this, "棋盘已满，没有可提示的点", Toast.LENGTH_SHORT).show();
                    refreshStatus();
                    return;
                }
                boardView.setHint(mv[0], mv[1]);
                setStatus("提示：" + coordinate(mv[0], mv[1]) + "（" + (color == GomokuGame.BLACK ? "黑" : "白") + "）");
            });
        }, "gomoku-hint").start();
    }

    private void onResign() {
        if (over) return;
        UiTheme t = theme != null ? theme : UiTheme.current(this);
        AlertDialog dlg = UiTheme.themedBuilder(this, t, "认输")
                .setMessage(isAiMode() ? "确定向 AI 认输?" :
                        (game.turn() == GomokuGame.BLACK ? "确定替黑方认输?" : "确定替白方认输?"))
                .setPositiveButton("认输", (d, w) -> {
                    int loser = isAiMode() ? playerColor : game.turn();
                    int winner = 3 - loser;
                    endResult = (winner == GomokuGame.BLACK ? "B" : "W") + "+R";
                    over = true;
                    aiGen++;
                    aiThinking = false;
                    boardView.setInputEnabled(false);
                    updateBackCallback();
                    setStatus(GomokuAi.levelName(level) + " · " + (loser == GomokuGame.BLACK ? "黑方" : "白方") + "认输");
                    endReason = (winner == GomokuGame.BLACK ? "黑方" : "白方") + "获胜（"
                            + (loser == GomokuGame.BLACK ? "黑方" : "白方") + "认输），共 "
                            + game.moveCount() + " 手。";
                    showResult(false);
                })
                .setNegativeButton("继续对局", null)
                .create();
        dlg.show();
        UiTheme.styleDialog(dlg, t, true);
    }

    private void finishByWin() {
        over = true;
        aiThinking = false;
        boardView.setInputEnabled(false);
        boardView.setGame(game);
        updateBackCallback();
        int w = game.winner();
        setStatus((w == GomokuGame.BLACK ? "黑方" : "白方") + "五连获胜");
        showResult(true);
    }

    private void showResult(boolean five) {
        if (resultShowing || isFinishing()) return;
        resultShowing = true;
        UiTheme t = theme != null ? theme : UiTheme.current(this);
        int w = game.winner();
        String title;
        String msg;
        if (five && w != GomokuGame.EMPTY) {
            if (isAiMode()) {
                title = w == playerColor ? "你赢了" : "AI 获胜";
                msg = (w == GomokuGame.BLACK ? "黑方" : "白方") + "五连，"
                        + (w == playerColor ? "恭喜拿下本局。" : "再来一局试试。");
            } else {
                title = (w == GomokuGame.BLACK ? "黑方" : "白方") + "获胜";
                msg = "五连达成，共 " + game.moveCount() + " 手。";
            }
        } else {
            title = "对局结束";
            if (endReason != null) {
                msg = endReason;
            } else {
                msg = "棋盘已满，和棋（共 " + game.moveCount() + " 手）。";
            }
        }
        AlertDialog dlg = UiTheme.themedBuilder(this, t, title)
                .setMessage(msg)
                .setPositiveButton("再来一局", (d, w2) -> startNewGame())
                .setNegativeButton("返回主页", (d, w2) -> finish())
                .create();
        dlg.setCancelable(false);
        dlg.show();
        UiTheme.styleDialog(dlg, t, false);
    }

    private void refreshStatus() {
        if (tvEyebrow != null) {
            tvEyebrow.setText("五 子 棋 · 第 " + han(game.moveCount()) + " 手");
        }
        tvStatusL.setText(isAiMode()
                ? "我执" + (playerColor == GomokuGame.BLACK ? "黑" : "白")
                : "本地双人");
        if (!over) {
            tvStatus.setText("轮到 " + (game.turn() == GomokuGame.BLACK ? "黑方" : "白方") + "行棋");
        }
        tvSubStatus.setText("19 路 · " + (renju ? "黑棋禁手" : "无禁手") + " · " + (isAiMode()
                ? "自研 AI · " + GomokuAi.levelName(level)
                : "同机双人"));
        if (backCallback != null) backCallback.setEnabled(!over);
    }

    private void setStatus(String s) {
        tvStatus.setText(s);
    }

    private void updateBackCallback() {
        if (backCallback != null) backCallback.setEnabled(!over);
    }

    private void confirmExit() {
        if (exitDialogShowing || isFinishing()) return;
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

    // ---------------- 禁手开关 ----------------

    private void toggleRenju() {
        if (aiThinking || replaying) return;
        if (game.moveCount() > 0) {
            Toast.makeText(this, "已有落子，切换禁手请先开新局", Toast.LENGTH_SHORT).show();
            return;
        }
        renju = !renju;
        game.setRenju(renju);
        updateRuleButton();
        refreshStatus();
        Toast.makeText(this, renju ? "黑棋禁手已开：长连 / 四四 / 三三判负"
                : "已切回无禁手（freestyle）", Toast.LENGTH_SHORT).show();
    }

    private void updateRuleButton() {
        updateRuleButton(theme != null ? theme : UiTheme.current(this));
    }

    private void updateRuleButton(UiTheme t) {
        if (t == null) return;
        styleButton(t, R.id.btnRule, renju ? "禁手开" : "禁手关",
                renju ? "RENJU ON" : "FREESTYLE", 0);
    }

    // ---------------- 存谱 / 读谱 ----------------

    private File recordDir() {
        return new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOCUMENTS), "清弈");
    }

    private void onSave() {
        if (replaying) return;
        if (game.moveCount() == 0) {
            Toast.makeText(this, "还没有落子，暂无可存的棋谱", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String blackName, whiteName;
            if (isAiMode()) {
                String ai = "自研AI " + GomokuAi.levelName(level);
                blackName = playerColor == GomokuGame.BLACK ? "我" : ai;
                whiteName = playerColor == GomokuGame.WHITE ? "我" : ai;
            } else {
                blackName = "黑方";
                whiteName = "白方";
            }
            String result = endResult != null && !endResult.isEmpty() ? endResult
                    : game.winner() == GomokuGame.BLACK ? "B+5"
                    : game.winner() == GomokuGame.WHITE ? "W+5" : null;
            File f = GomokuRecord.saveToFile(game, blackName, whiteName, result, recordDir());
            Toast.makeText(this, "已保存: " + f.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void onLoad() {
        if (replaying) return;
        File[] files = GomokuRecord.list(recordDir());
        if (files.length == 0) {
            Toast.makeText(this, "目录里还没有 .gmk 棋谱", Toast.LENGTH_LONG).show();
            return;
        }
        final String[] names = new String[files.length];
        for (int i = 0; i < files.length; i++) {
            String n = files[i].getName();
            names[i] = n.endsWith(GomokuRecord.EXT)
                    ? n.substring(0, n.length() - GomokuRecord.EXT.length()) : n;
        }
        UiTheme t = theme != null ? theme : UiTheme.current(this);
        AlertDialog dlg = UiTheme.themedBuilder(this, t, "读取棋谱（逐手回放）")
                .setItems(names, (d, which) -> replayRecord(files[which]))
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
        UiTheme.styleDialog(dlg, t, false);
    }

    private void replayRecord(File f) {
        final GomokuRecord.Data rec;
        try {
            rec = GomokuRecord.load(f);
        } catch (Exception e) {
            Toast.makeText(this, "棋谱读取失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        if (rec.moves.isEmpty()) {
            Toast.makeText(this, "棋谱里没有着法", Toast.LENGTH_SHORT).show();
            return;
        }
        aiGen++;
        aiThinking = false;
        hintWorking = false;
        resultShowing = false;
        endReason = null;
        endResult = rec.result == null ? "" : rec.result;
        replaying = true;
        over = true;
        renju = rec.renju;
        final int gen = ++replayGen;
        game.clear();
        game.setRenju(renju);
        boardView.clearHint();
        boardView.setGame(game);
        boardView.setInputEnabled(false);
        updateRuleButton();
        updateBackCallback();
        setStatus("回放 0 / " + rec.moves.size());
        tvSubStatus.setText("19 路 · " + (renju ? "黑棋禁手" : "无禁手") + " · 棋谱回放");
        ui.postDelayed(() -> replayStep(rec, 0, gen), 120);
    }

    private void replayStep(GomokuRecord.Data rec, int idx, int gen) {
        if (gen != replayGen || isFinishing() || isDestroyed()) return;
        if (idx >= rec.moves.size()) {
            replaying = false;
            over = game.isOver();
            boardView.setGame(game);
            boardView.setInputEnabled(!over);
            updateBackCallback();
            refreshStatus();
            setStatus("回放完成 · 共 " + rec.moves.size() + " 手" + (over ? "" : "（可继续对弈）"));
            if (!over && isAiMode() && game.turn() != playerColor) aiMove();
            return;
        }
        int[] m = rec.moves.get(idx);
        game.play(m[1], m[2]);
        boardView.setGame(game);
        tvEyebrow.setText("棋 谱 · 第 " + han(game.moveCount()) + " 手");
        setStatus("回放 " + (idx + 1) + " / " + rec.moves.size());
        ui.postDelayed(() -> replayStep(rec, idx + 1, gen), 140);
    }

    // ---------------- 主题 ----------------

    private void applyTheme(UiTheme t) {
        theme = t;
        getWindow().setStatusBarColor(t.windowBg);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        getWindow().getDecorView().setBackgroundColor(t.windowBg);
        tvStatus.setTextColor(t.titleColor);
        float d = getResources().getDisplayMetrics().density;
        boolean hara = t.id == UiTheme.HARA;
        android.graphics.Typeface serif = android.graphics.Typeface.create("serif", android.graphics.Typeface.BOLD);

        findViewById(R.id.rowEyebrow).setVisibility(hara ? View.VISIBLE : View.GONE);
        View divTop = findViewById(R.id.divTop);
        divTop.setVisibility(View.VISIBLE);
        divTop.setBackgroundColor(hara ? t.dividerColor : t.dlgLine);
        findViewById(R.id.tvSign).setVisibility(hara ? View.VISIBLE : View.GONE);
        ((TextView) findViewById(R.id.tvSign)).setTextColor(t.footColor);
        findViewById(R.id.tvMotto).setVisibility(hara ? View.GONE : View.VISIBLE);
        ((TextView) findViewById(R.id.tvMotto)).setTextColor(t.footColor);
        tvSubStatus.setVisibility(hara ? View.GONE : View.VISIBLE);
        tvSubStatus.setTextColor(t.subColor);
        tvEyebrow.setTextColor(t.subColor);

        android.graphics.drawable.GradientDrawable sg = new android.graphics.drawable.GradientDrawable();
        sg.setColor(t.accent);
        sg.setCornerRadius(2 * d);
        findViewById(R.id.tvSealMini).setBackground(sg);

        tvStatusL.setTextColor(t.titleColor);
        tvStatusL.setTypeface(serif);
        tvStatus.setTypeface(serif);
        if (hara) {
            tvStatusL.setVisibility(View.VISIBLE);
            tvStatus.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            tvStatus.setTextSize(17);
        } else {
            tvStatusL.setVisibility(View.GONE);
            tvStatus.setGravity(Gravity.CENTER);
            tvStatus.setTextSize(19);
        }

        boardView.setThemeColors(t.boardBg, t.boardLine, t.marker);
        View ped = findViewById(R.id.pedestal);
        if (t.id == UiTheme.MONUMENT) {
            android.graphics.drawable.GradientDrawable pg = new android.graphics.drawable.GradientDrawable();
            pg.setColor(android.graphics.Color.parseColor("#D8C9B8"));
            pg.setCornerRadius(5 * d);
            ped.setBackground(pg);
            ped.setVisibility(View.VISIBLE);
        } else {
            ped.setVisibility(View.GONE);
        }

        styleButton(t, R.id.btnUndo, "悔棋", "UNDO", 0);
        styleButton(t, R.id.btnHint, "提示", "HINT", 0);
        styleButton(t, R.id.btnResign, "认输", "RESIGN", 2);
        styleButton(t, R.id.btnNew, "新局", "NEW", 1);
        styleButton(t, R.id.btnSave, "存谱", "SAVE SGF", 0);
        styleButton(t, R.id.btnLoad, "读谱", "OPEN SGF", 0);
        updateRuleButton(t);

        findViewById(R.id.btnHint).setVisibility(hintsEnabled ? View.VISIBLE : View.GONE);

        int gridVis = hara ? View.VISIBLE : View.GONE;
        int[] gridLines = {R.id.gridTop, R.id.gridMid, R.id.gridBottom,
                R.id.vG1, R.id.vG2, R.id.vG3, R.id.vG4, R.id.vG5};
        for (int id : gridLines) {
            View v = findViewById(id);
            v.setVisibility(gridVis);
            v.setBackgroundColor(t.dividerColor);
        }
        View row1 = findViewById(R.id.row1);
        android.widget.LinearLayout.LayoutParams rlp =
                (android.widget.LinearLayout.LayoutParams) row1.getLayoutParams();
        rlp.topMargin = (int) ((hara ? 0 : 40) * d);
        row1.setLayoutParams(rlp);
    }

    private void styleButton(UiTheme t, int id, String zh, String en, int kind) {
        Button b = findViewById(id);
        b.setBackground(t.buttonDrawable(kind));
        float d = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout.LayoutParams lp =
                (android.widget.LinearLayout.LayoutParams) b.getLayoutParams();
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

    private String coordinate(int x, int y) {
        return String.valueOf((char) ('A' + x)) + (19 - y);
    }

    private static String han(int n) {
        String[] d = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
        if (n <= 10) return n == 10 ? "十" : d[n];
        if (n < 20) return "十" + d[n - 10];
        if (n < 100) {
            int a = n / 10, b = n % 10;
            return d[a] + "十" + (b == 0 ? "" : d[b]);
        }
        return String.valueOf(n);
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

    // ---------------- 音效 ----------------

    private void initSounds() {
        soundPool = new SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        sndStone = soundPool.load(this, R.raw.xq_move, 1);
    }

    private void playStone() {
        if (soundPool != null) soundPool.play(sndStone, 0.7f, 0.7f, 1, 0, 1f);
    }

    @Override
    protected void onDestroy() {
        aiGen++;
        replayGen++;
        ui.removeCallbacksAndMessages(null);
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
        super.onDestroy();
    }
}
