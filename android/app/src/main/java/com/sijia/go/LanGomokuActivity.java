package com.sijia.go;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;

import com.sijia.go.net.GameAdapter;
import com.sijia.go.net.GomokuRules;
import com.sijia.go.net.LanHub;

import java.io.File;

/**
 * 联机五子棋对局页。
 * 网络层完全复用 LanHub：game=gomoku、4 位房号、mDNS/UDP 发现、握手、重连、房主权威裁决。
 */
public class LanGomokuActivity extends ComponentActivity {
    private UiTheme t;
    private LanHub hub;
    private GomokuBoardView boardView;
    private TextView tvStatus, tvStatusL, tvConn, tvSubStatus, tvClockB, tvClockW, tvHint, tvTitle;

    private boolean resultShown;
    private boolean hintWorking;
    private int hintMoveNo = -1;
    private SoundPool soundPool;
    private int sndStone, sndWin, sndLose;

    private final LanHub.Listener listener = new LanHub.Listener() {
        @Override
        public void onStateChanged() {
            ui(LanGomokuActivity.this::render);
        }

        @Override
        public void onMove() {
            ui(() -> {
                playStoneSound();
                render();
            });
        }

        @Override
        public void onClock() {
            ui(LanGomokuActivity.this::renderClocks);
        }

        @Override
        public void onGameOver(String result, String detail) {
            ui(() -> {
                playResultSound(result);
                render();
                showResult();
            });
        }

        @Override
        public void onUndoRequest(String requester) {
            ui(() -> askUndo(requester));
        }

        @Override
        public void onNotice(String message) {
            ui(() -> Toast.makeText(LanGomokuActivity.this, message, Toast.LENGTH_SHORT).show());
        }

        @Override
        public void onError(String message) {
            ui(() -> Toast.makeText(LanGomokuActivity.this, message, Toast.LENGTH_LONG).show());
        }
    };

    private void ui(Runnable action) {
        if (isFinishing() || isDestroyed()) return;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            try {
                action.run();
            } catch (Throwable e) {
                android.util.Log.e("LanGomokuActivity", "界面回调异常", e);
            }
        });
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_lan_gomoku);
        hub = LanHub.get();
        if (hub.session() == null || !GameAdapter.GOMOKU.equals(hub.gameId())) {
            android.util.Log.w("LanGomokuActivity", "进入对局页但无五子棋会话，直接结束 isTaskRoot=" + isTaskRoot());
            Toast.makeText(this, "对局已结束", Toast.LENGTH_SHORT).show();
            if (isTaskRoot()) startActivity(new android.content.Intent(this, MainActivity.class));
            finish();
            return;
        }
        hub.addListener(listener);
        initSounds();

        boardView = findViewById(R.id.boardView);
        tvStatus = findViewById(R.id.tvStatus);
        tvStatusL = findViewById(R.id.tvStatusL);
        tvConn = findViewById(R.id.tvConn);
        tvSubStatus = findViewById(R.id.tvSubStatus);
        tvClockB = findViewById(R.id.tvClockB);
        tvClockW = findViewById(R.id.tvClockW);
        tvHint = findViewById(R.id.tvHint);
        tvTitle = findViewById(R.id.tvTitle);

        boardView.setOnBoardTapListener((x, y) -> {
            if (hub.isPlaying() && !hub.linkDown() && hub.isMyTurn() && !hub.pendingMove()) {
                GomokuRules rules = hub.rules() instanceof GomokuRules ? (GomokuRules) hub.rules() : null;
                if (rules != null && rules.isRenju() && hub.myColor() == GomokuGame.BLACK
                        && GomokuGame.isForbiddenPoint(rules.game().board(), x, y)) {
                    Toast.makeText(this, rules.game().forbiddenText(x, y), Toast.LENGTH_SHORT).show();
                    return;
                }
                hub.requestMove(x, y);
            }
        });

        findViewById(R.id.btnUndo).setOnClickListener(v -> {
            if (!hub.isPlaying()) return;
            if (hub.moveNo() <= 0) {
                Toast.makeText(this, "还没有落子", Toast.LENGTH_SHORT).show();
                return;
            }
            hub.requestUndo();
        });
        findViewById(R.id.btnHint).setOnClickListener(v -> requestHint());
        findViewById(R.id.btnResign).setOnClickListener(v -> confirmResign());
        findViewById(R.id.btnSave).setOnClickListener(v -> onSave());
        findViewById(R.id.btnLeave).setOnClickListener(v -> confirmLeave());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                confirmLeave();
            }
        });

        applyTheme(UiTheme.current(this));
        render();
        if (hub.state() == LanHub.FINISHED && !hub.resultDetail().isEmpty()) showResult();
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
        hub.removeListener(listener);
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
        if (isFinishing()) hub.stop();
    }

    private void initSounds() {
        soundPool = new SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        sndStone = soundPool.load(this, R.raw.xq_move, 1);
        sndWin = soundPool.load(this, R.raw.go_win, 1);
        sndLose = soundPool.load(this, R.raw.go_lose, 1);
    }

    private void playStoneSound() {
        if (soundPool != null) soundPool.play(sndStone, 0.7f, 0.7f, 1, 0, 1f);
    }

    private void playResultSound(String result) {
        if (soundPool == null) return;
        boolean win = result != null && result.startsWith(
                hub.myColor() == GomokuGame.BLACK ? "B+" : "W+");
        soundPool.play(win ? sndWin : sndLose, 0.85f, 0.85f, 2, 0, 1f);
    }

    // ---------------- 渲染 ----------------

    private void render() {
        if (t == null) return;
        GomokuGame g = hub.gomoku();
        if (g == null) {
            if (!isFinishing()) finish();
            return;
        }
        if (hintMoveNo != hub.moveNo()) {
            hintMoveNo = hub.moveNo();
            boardView.clearHint();
        }
        boardView.setGame(g);
        boardView.setInputEnabled(hub.isPlaying() && !hub.linkDown()
                && !hub.scorePending() && hub.isMyTurn() && !hub.pendingMove());

        boolean hara = t.hairlineList;
        tvStatusL.setVisibility(View.VISIBLE);
        tvStatusL.setText("我执" + hub.myColorName());
        String st;
        if (!hub.isPlaying()) st = hub.state() == LanHub.FINISHED ? "对局结束" : "等待连接";
        else if (hub.linkDown()) st = "重连中…";
        else if (hub.isMyTurn()) st = hub.pendingMove() ? "已发送…" : "轮到你落子";
        else st = "等待对手落子";
        tvStatus.setText(st);
        tvStatus.setGravity(hara
                ? (android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL)
                : android.view.Gravity.CENTER);
        tvStatus.setTypeface(Typeface.create("serif", hara ? Typeface.NORMAL : Typeface.BOLD));

        tvConn.setText("我执" + hub.myColorName() + " · 对手 " + hub.opponentName());
        tvTitle.setText("五子棋 · 房号 " + hub.roomCode());
        boolean renju = hub.rules() instanceof GomokuRules && ((GomokuRules) hub.rules()).isRenju();
        tvSubStatus.setText("19 路 · " + (renju ? "黑棋禁手" : "无禁手")
                + " · " + (hub.timerEnabled() ? "计时" : "不计时"));

        ((TextView) findViewById(R.id.tvEyebrow)).setText(
                "联 机 · 第 " + Math.max(1, hub.moveNo()) + " 手 · 房 " + hub.roomCode());

        GameAdapter.MoveInfo last = hub.lastMove();
        if (last == null) {
            tvHint.setText("开局 · 黑先");
        } else if (last.pass || last.fx < 0) {
            tvHint.setText("第 " + hub.moveNo() + " 手 · 停一手");
        } else {
            tvHint.setText("第 " + hub.moveNo() + " 手 · " + hub.colorName(last.seat)
                    + " " + coordinate(last.fx, last.fy));
        }

        boolean canAct = hub.isPlaying() && !hub.linkDown() && !hub.scorePending();
        findViewById(R.id.btnUndo).setEnabled(canAct);
        findViewById(R.id.btnResign).setEnabled(canAct);
        Button bh = findViewById(R.id.btnHint);
        bh.setVisibility(hub.hintsEnabled() ? View.VISIBLE : View.GONE);
        bh.setEnabled(canAct && !hintWorking);
        renderClocks();
    }

    private void renderClocks() {
        if (t == null) return;
        int turn = hub.isPlaying() ? hub.turn() : -1;
        boolean playing = hub.isPlaying();
        float d = getResources().getDisplayMetrics().density;
        boolean hara = t.hairlineList;

        tvClockB.setText(hub.clockLabel(GomokuGame.BLACK));
        tvClockW.setText(hub.clockLabel(GomokuGame.WHITE));
        boolean bActive = playing && turn == GomokuGame.BLACK;
        boolean wActive = playing && turn == GomokuGame.WHITE;

        if (hara) {
            tvClockB.setBackgroundColor(Color.TRANSPARENT);
            tvClockW.setBackgroundColor(Color.TRANSPARENT);
        } else {
            GradientDrawable bgB = new GradientDrawable();
            bgB.setColor(bActive ? t.primaryBg : t.entryBg[0]);
            bgB.setCornerRadius(18 * d);
            tvClockB.setBackground(bgB);
            GradientDrawable bgW = new GradientDrawable();
            bgW.setColor(wActive ? t.primaryBg : t.entryBg[1]);
            bgW.setCornerRadius(18 * d);
            tvClockW.setBackground(bgW);
        }
        tvClockB.setTextColor(bActive ? (hara ? t.accent : t.primaryText) : t.subColor);
        tvClockW.setTextColor(wActive ? (hara ? t.accent : t.primaryText) : t.subColor);
        tvClockB.setTypeface(Typeface.create("serif", bActive ? Typeface.BOLD : Typeface.NORMAL));
        tvClockW.setTypeface(Typeface.create("serif", wActive ? Typeface.BOLD : Typeface.NORMAL));
    }

    // ---------------- 操作 ----------------

    private void requestHint() {
        if (!hub.hintsEnabled() || hintWorking) return;
        if (!hub.isPlaying() || hub.linkDown() || hub.pendingMove()) return;
        GomokuGame g = hub.gomoku();
        if (g == null) return;
        hintWorking = true;
        final int[][] snap = g.copyBoard();
        // 统一为「当前该走的一方」计算, 两端同一局面必定得到同一个点
        final int color = hub.turn();
        final int moveNo = hub.moveNo();
        boolean renju = hub.rules() instanceof GomokuRules && ((GomokuRules) hub.rules()).isRenju();
        final GomokuAi.MoveFilter filter = (renju && color == GomokuGame.BLACK)
                ? (x, y) -> !GomokuGame.isForbiddenPoint(snap, x, y) : null;
        ((Button) findViewById(R.id.btnHint)).setEnabled(false);
        new Thread(() -> {
            int[] mv = GomokuAi.choose(snap, color, 4, filter);
            ui(() -> {
                hintWorking = false;
                if (isFinishing() || isDestroyed()) return;
                if (!hub.isPlaying() || hub.moveNo() != moveNo) {
                    render();
                    return;
                }
                if (mv == null) {
                    Toast.makeText(this, "没有可提示的点", Toast.LENGTH_SHORT).show();
                    render();
                    return;
                }
                boardView.setHint(mv[0], mv[1]);
                tvHint.setText("提示：" + coordinate(mv[0], mv[1]) + "（仅本机显示）");
                findViewById(R.id.btnHint).setEnabled(false);
            });
        }, "gomoku-lan-hint").start();
    }

    private void askUndo(String who) {
        if (isFinishing() || isDestroyed()) return;
        AlertDialog dlg = UiTheme.themedBuilder(this, t, "悔棋请求")
                .setMessage(who + " 请求悔一手，是否同意？")
                .setNegativeButton("拒绝", (d, w) -> hub.respondUndo(false))
                .setPositiveButton("同意", (d, w) -> hub.respondUndo(true))
                .create();
        dlg.show();
        UiTheme.styleDialog(dlg, t, false);
    }

    private void confirmResign() {
        AlertDialog dlg = UiTheme.themedBuilder(this, t, "认输")
                .setMessage("确认认输？本局将判负。")
                .setNegativeButton("再想想", null)
                .setPositiveButton("认输", (d, w) -> hub.resign())
                .create();
        dlg.show();
        UiTheme.styleDialog(dlg, t, true);
    }

    private void confirmLeave() {
        if (isFinishing() || isDestroyed()) return;
        if (!hub.isPlaying()) {
            hub.stop();
            finish();
            return;
        }
        AlertDialog dlg = UiTheme.themedBuilder(this, t, "离开对局")
                .setMessage("对局尚未结束，离开将中断连接。")
                .setNegativeButton("留下", null)
                .setPositiveButton("离开", (d, w) -> {
                    hub.stop();
                    finish();
                })
                .create();
        dlg.show();
        UiTheme.styleDialog(dlg, t, true);
    }

    private void showResult() {
        if (resultShown || isFinishing()) return;
        resultShown = true;
        AlertDialog dlg = UiTheme.themedBuilder(this, t, "对局结束")
                .setMessage(hub.resultText())
                .setNegativeButton("查看棋盘", null)
                .setPositiveButton("离开", (d, w) -> {
                    hub.stop();
                    finish();
                })
                .create();
        dlg.show();
        UiTheme.styleDialog(dlg, t, false);
    }

    private void onSave() {
        GomokuGame g = hub.gomoku();
        if (g == null || g.moveCount() == 0) {
            Toast.makeText(this, "还没有落子，暂无可存的棋谱", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            int me = hub.myColor();
            String mine = "我（" + hub.myColorName() + "）";
            String opp = hub.opponentName();
            String blackName = me == GomokuGame.BLACK ? mine : opp;
            String whiteName = me == GomokuGame.WHITE ? mine : opp;
            String result = hub.resultCode();
            if (result == null || result.isEmpty()) {
                result = g.winner() == GomokuGame.BLACK ? "B+5"
                        : g.winner() == GomokuGame.WHITE ? "W+5" : null;
            }
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOCUMENTS), "清弈");
            File f = GomokuRecord.saveToFile(g, blackName, whiteName, result, dir);
            Toast.makeText(this, "已保存: " + f.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String coordinate(int x, int y) {
        return String.valueOf((char) ('A' + x)) + (19 - y);
    }

    // ---------------- 主题 ----------------

    private void applyTheme(UiTheme theme) {
        this.t = theme;
        float d = getResources().getDisplayMetrics().density;
        boolean hara = theme.hairlineList;

        getWindow().setStatusBarColor(theme.windowBg);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        getWindow().getDecorView().setBackgroundColor(theme.windowBg);
        findViewById(R.id.root).setBackgroundColor(theme.windowBg);
        findViewById(R.id.rowEyebrow).setVisibility(View.GONE);
        ((TextView) findViewById(R.id.tvEyebrow)).setTextColor(theme.subColor);

        GradientDrawable sg = new GradientDrawable();
        sg.setColor(theme.accent);
        sg.setCornerRadius(2 * d);
        findViewById(R.id.tvSealMini).setBackground(sg);

        tvStatus.setTextColor(theme.titleColor);
        tvStatusL.setTextColor(theme.titleColor);
        tvStatusL.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        tvSubStatus.setTextColor(theme.subColor);
        tvConn.setTextColor(theme.subColor);
        tvHint.setTextColor(theme.subColor);

        View divTop = findViewById(R.id.divTop);
        divTop.setVisibility(View.VISIBLE);
        divTop.setBackgroundColor(hara ? theme.dividerColor : theme.dlgLine);
        findViewById(R.id.clockDivider).setBackgroundColor(theme.dividerColor);

        android.widget.LinearLayout.LayoutParams blp =
                (android.widget.LinearLayout.LayoutParams) boardView.getLayoutParams();
        blp.leftMargin = 0;
        blp.rightMargin = 0;
        boardView.setLayoutParams(blp);
        boardView.setThemeColors(theme.boardBg, theme.boardLine, theme.marker);

        View ped = findViewById(R.id.pedestal);
        if (theme.id == UiTheme.MONUMENT) {
            GradientDrawable pg = new GradientDrawable();
            pg.setColor(Color.parseColor("#D8C9B8"));
            pg.setCornerRadius(5 * d);
            ped.setBackground(pg);
            ped.setVisibility(View.VISIBLE);
        } else {
            ped.setVisibility(View.GONE);
        }

        styleButton(theme, R.id.btnUndo, "悔棋", "UNDO", theme.id == UiTheme.MONUMENT ? 1 : 0);
        styleButton(theme, R.id.btnHint, "提示", "HINT", 0);
        styleButton(theme, R.id.btnResign, "认输", "RESIGN", 2);
        styleButton(theme, R.id.btnSave, "存谱", "SAVE SGF", 0);
        styleButton(theme, R.id.btnLeave, "离开", "LEAVE", 0);

        findViewById(R.id.tvSign).setVisibility(hara ? View.VISIBLE : View.GONE);
        ((TextView) findViewById(R.id.tvSign)).setTextColor(theme.footColor);

        int gridVis = hara ? View.VISIBLE : View.GONE;
        int[] gridLines = {R.id.gridTop, R.id.gridMid, R.id.gridBottom,
                R.id.vG1, R.id.vG2, R.id.vG3};
        for (int id : gridLines) {
            View v = findViewById(id);
            v.setVisibility(gridVis);
            v.setBackgroundColor(theme.dividerColor);
        }
        View row1 = findViewById(R.id.row1);
        android.widget.LinearLayout.LayoutParams rlp =
                (android.widget.LinearLayout.LayoutParams) row1.getLayoutParams();
        rlp.topMargin = (int) ((hara ? 0 : 10) * d);
        row1.setLayoutParams(rlp);
    }

    private void styleButton(UiTheme theme, int id, String zh, String en, int kind) {
        Button b = findViewById(id);
        b.setBackground(theme.buttonDrawable(kind));
        float d = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout.LayoutParams lp =
                (android.widget.LinearLayout.LayoutParams) b.getLayoutParams();
        if (theme.hairlineList) {
            lp.setMargins(0, 0, 0, 0);
            android.text.SpannableString s = new android.text.SpannableString(zh + "\n" + en);
            s.setSpan(new android.text.style.RelativeSizeSpan(0.62f), zh.length() + 1, s.length(), 0);
            s.setSpan(new android.text.style.ForegroundColorSpan(theme.subColor), zh.length() + 1, s.length(), 0);
            b.setText(s);
            b.setPadding(0, (int) (7 * d), 0, (int) (7 * d));
        } else {
            int m = (int) (6 * d);
            lp.setMargins(m, m, m, m);
            b.setText(zh);
            b.setPadding(0, (int) (6 * d), 0, (int) (6 * d));
        }
        b.setLayoutParams(lp);
        b.setTextColor(theme.buttonTextColor(kind));
    }
}
