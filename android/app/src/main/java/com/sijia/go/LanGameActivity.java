package com.sijia.go;

import android.app.Activity;
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

import com.sijia.go.net.GameAdapter;
import com.sijia.go.net.LanHub;

import java.io.File;

/**
 * 联机对局页：棋盘镜像显示、时钟、悔棋/停一手/认输/数子/存谱。
 * 权威裁决在房主侧，局域网模块见 net/LanHub。
 */
public class LanGameActivity extends Activity {
    private UiTheme t;
    private LanHub hub;
    private GoBoardView boardView;
    private TextView tvStatus, tvStatusL, tvConn, tvSubStatus, tvClockB, tvClockW, tvHint;

    private boolean resultShown;
    private SoundPool soundPool;
    private int sndStone, sndCheck, sndWin, sndLose;

    private final LanHub.Listener listener = new LanHub.Listener() {
        @Override
        public void onStateChanged() {
            ui(LanGameActivity.this::render);
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
            ui(LanGameActivity.this::renderClocks);
        }

        @Override
        public void onGameOver(String result, String detail) {
            ui(() -> {
                playGoResultSound(result);
                render();
                showResult(detail);
            });
        }

        @Override
        public void onUndoRequest(String requester) {
            ui(() -> askUndo(requester));
        }

        @Override
        public void onNotice(String message) {
            ui(() -> Toast.makeText(LanGameActivity.this, message, Toast.LENGTH_SHORT).show());
        }

        @Override
        public void onError(String message) {
            ui(() -> Toast.makeText(LanGameActivity.this, message, Toast.LENGTH_LONG).show());
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
                android.util.Log.e("LanGameActivity", "界面回调异常", t);
            }
        });
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_lan_game);
        hub = LanHub.get();
        if (hub.session() == null || !GameAdapter.GO.equals(hub.gameId())) {
            // 进程被回收后重建时 session 已丢失：直接 finish 会让任务栈变空看起来像"App 退出"
            android.util.Log.w("LanGameActivity", "进入对局页但无会话，直接结束 isTaskRoot=" + isTaskRoot());
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

        boardView.setOnBoardTapListener((x, y) -> {
            if (hub.isPlaying() && !hub.linkDown() && hub.isMyTurn() && !hub.pendingMove()) {
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
        findViewById(R.id.btnPass).setOnClickListener(v -> {
            if (hub.isPlaying()) hub.requestPass();
        });
        findViewById(R.id.btnResign).setOnClickListener(v -> confirmResign());
        findViewById(R.id.btnScore).setOnClickListener(v -> hub.requestScore());
        findViewById(R.id.btnSave).setOnClickListener(v -> saveSgf());
        findViewById(R.id.btnLeave).setOnClickListener(v -> confirmLeave());

        applyTheme(UiTheme.current(this));
        render();
        if (hub.state() == LanHub.FINISHED && !hub.resultDetail().isEmpty()) {
            showResult(hub.resultDetail());
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
        hub.removeListener(listener);
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
        if (isFinishing()) hub.stop();
    }

    /** 联机落子音：之前 LanGameActivity 完全没有音效调用，双方都听不到落子声。 */
    private void initSounds() {
        soundPool = new SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        sndStone = soundPool.load(this, R.raw.xq_move, 1);
        sndCheck = soundPool.load(this, R.raw.xq_check, 1);
        sndWin = soundPool.load(this, R.raw.go_win, 1);
        sndLose = soundPool.load(this, R.raw.go_lose, 1);
    }

    private void playStoneSound() {
        if (soundPool != null) soundPool.play(sndStone, 0.7f, 0.7f, 1, 0, 1f);
    }

    /** 围棋终局：赢家喝彩、输家收束，按座位区分，不再播象棋将军音。 */
    private void playGoResultSound(String result) {
        if (soundPool == null) return;
        boolean win;
        if (result != null && result.startsWith("B+")) win = hub.myColor() == GoGame.BLACK;
        else if (result != null && result.startsWith("W+")) win = hub.myColor() == GoGame.WHITE;
        else win = false;
        soundPool.play(win ? sndWin : sndLose, 0.85f, 0.85f, 2, 0, 1f);
    }

    @Override
    public void onBackPressed() {
        confirmLeave();
    }

    // ---------------- 渲染 ----------------

    private void render() {
        if (t == null) return;
        GoGame g = hub.game();
        if (g == null) {
            if (!isFinishing()) finish();
            return;
        }
        boardView.setBoardSize(g.size());
        GameAdapter.MoveInfo last = hub.lastMove();
        if (last == null || last.pass || last.fx < 0) {
            boardView.setBoard(g.board(), -1, -1);
        } else {
            boardView.setBoard(g.board(), last.fx, last.fy);
        }
        boardView.setInputEnabled(hub.isPlaying() && !hub.linkDown()
                && !hub.scorePending() && hub.isMyTurn() && !hub.pendingMove());

        boolean hara = t.hairlineList;
        tvStatusL.setVisibility(View.VISIBLE);
        tvStatusL.setText("我执" + hub.myColorName());
        if (hub.scorePending()) {
            // 右侧列定宽 76dp（约 5 个字）：文案控制在 6 字以内，避免被省略号截断
            tvStatus.setText(hub.isHost() ? "请确认数子" : "等待确认");
            tvStatus.setGravity(hara ? (android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL) : android.view.Gravity.CENTER);
            tvStatus.setTypeface(Typeface.create("serif", hara ? Typeface.NORMAL : Typeface.BOLD));
        } else if (!hub.isPlaying()) {
            tvStatus.setText(hub.state() == LanHub.FINISHED ? "对局结束" : "等待连接");
            tvStatus.setGravity(hara ? (android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL) : android.view.Gravity.CENTER);
            tvStatus.setTypeface(Typeface.create("serif", hara ? Typeface.NORMAL : Typeface.BOLD));
        } else if (hub.linkDown()) {
            tvStatus.setText("重连中…");
            tvStatus.setGravity(hara ? (android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL) : android.view.Gravity.CENTER);
            tvStatus.setTypeface(Typeface.create("serif", hara ? Typeface.NORMAL : Typeface.BOLD));
        } else if (hub.isMyTurn()) {
            tvStatus.setText(hub.pendingMove() ? "已发送…" : "轮到你落子");
            tvStatus.setGravity(hara ? (android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL) : android.view.Gravity.CENTER);
            tvStatus.setTypeface(Typeface.create("serif", hara ? Typeface.NORMAL : Typeface.BOLD));
        } else {
            tvStatus.setText("等待对手落子");
            tvStatus.setGravity(hara ? (android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL) : android.view.Gravity.CENTER);
            tvStatus.setTypeface(Typeface.create("serif", hara ? Typeface.NORMAL : Typeface.BOLD));
        }

        String role = hub.isHost() ? "房主" : "客人";
        String conn = hub.linkDown() ? "连接中断"
                : (hub.opponentName().equals("对手") ? "等待对手加入" : "已连接");
        tvConn.setText("我执" + hub.myColorName() + " · 对手 " + hub.opponentName());

        TextView tvTitle = findViewById(R.id.tvTitle);
        tvTitle.setText((g.size() == 9 ? "象棋" : "围棋") + " · 房号 " + hub.roomCode());

        tvSubStatus.setText(g.size() + " 路 · 中国规则 · 贴 " + trimKomi(hub.komi())
                + " 目 · " + (hub.timerEnabled() ? "计时" : "不计时"));

        // 三列页头：状态文字统一右对齐，与效果图一致
        tvStatus.setGravity(android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);

        String eyebrow = "联 机 · 第 " + Math.max(1, hub.moveNo()) + " 手 · 房 " + hub.roomCode();
        ((TextView) findViewById(R.id.tvEyebrow)).setText(eyebrow);

        if (last == null) {
            tvHint.setText("开局 · 黑先");
        } else if (last.pass || last.fx < 0) {
            tvHint.setText("第 " + hub.moveNo() + " 手 · " + hub.colorName(last.seat) + "方停一手");
        } else {
            String cap = last.captured == 0 ? "" : " · 提 " + last.captured + " 子";
            tvHint.setText("第 " + hub.moveNo() + " 手 · " + hub.colorName(last.seat)
                    + " " + g.toGtp(last.fx, last.fy) + cap);
        }

        boolean canAct = hub.isPlaying() && !hub.linkDown() && !hub.scorePending();
        findViewById(R.id.btnUndo).setEnabled(canAct);
        findViewById(R.id.btnPass).setEnabled(canAct);
        findViewById(R.id.btnResign).setEnabled(canAct);
        // 待确认阶段只有房主能点「数子」完成确认
        findViewById(R.id.btnScore).setEnabled(canAct || (hub.scorePending() && hub.isHost()));
        ((Button) findViewById(R.id.btnScore)).setText(
                hub.scorePending() && hub.isHost() ? "确认数子" : "数子");
        renderClocks();
    }

    private void renderClocks() {
        if (t == null) return;
        int turn = hub.isPlaying() ? hub.turn() : -1;
        boolean playing = hub.isPlaying();
        float d = getResources().getDisplayMetrics().density;
        boolean hara = t.hairlineList;

        tvClockB.setText(hub.clockLabel(GoGame.BLACK));
        tvClockW.setText(hub.clockLabel(GoGame.WHITE));
        boolean bActive = playing && turn == GoGame.BLACK;
        boolean wActive = playing && turn == GoGame.WHITE;

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
            android.view.ViewGroup.MarginLayoutParams lp =
                    (android.view.ViewGroup.MarginLayoutParams) tvClockB.getLayoutParams();
            lp.setMargins((int) (4 * d), 0, (int) (4 * d), 0);
            tvClockB.setLayoutParams(lp);
            android.view.ViewGroup.MarginLayoutParams lp2 =
                    (android.view.ViewGroup.MarginLayoutParams) tvClockW.getLayoutParams();
            lp2.setMargins((int) (4 * d), 0, (int) (4 * d), 0);
            tvClockW.setLayoutParams(lp2);
        }
        tvClockB.setTextColor(bActive ? (hara ? t.accent : t.primaryText) : t.subColor);
        tvClockW.setTextColor(wActive ? (hara ? t.accent : t.primaryText) : t.subColor);
        tvClockB.setTypeface(Typeface.create("serif", bActive ? Typeface.BOLD : Typeface.NORMAL));
        tvClockW.setTypeface(Typeface.create("serif", wActive ? Typeface.BOLD : Typeface.NORMAL));
    }

    private String trimKomi(double k) {
        return (k == Math.floor(k)) ? String.valueOf((int) k) : String.valueOf(k);
    }

    // ---------------- 操作 ----------------

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

    private void showResult(String detail) {
        if (resultShown || isFinishing()) return;
        resultShown = true;
        AlertDialog dlg = UiTheme.themedBuilder(this, t, "对局结束")
                .setMessage(hub.resultText())
                .setNegativeButton("查看棋盘", null)
                .setPositiveButton("存谱", (d, w) -> saveSgf())
                .create();
        dlg.show();
        UiTheme.styleDialog(dlg, t, false);
    }

    private void saveSgf() {
        GoGame g = hub.game();
        if (g == null) return;
        String me = hub.localName();
        String op = hub.opponentName();
        String black = hub.myColor() == GoGame.BLACK ? me : op;
        String white = hub.myColor() == GoGame.BLACK ? op : me;
        String result = hub.resultCode();
        if ("draw".equals(result)) result = "0";
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOCUMENTS), "清弈");
            File f = Sgf.saveToFile(g, black, white, result, dir);
            Toast.makeText(this, "已保存 " + f.getName(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ---------------- 主题 ----------------

    private void applyTheme(UiTheme t) {
        this.t = t;
        float d = getResources().getDisplayMetrics().density;
        boolean hara = t.hairlineList;
        Typeface serif = Typeface.create("serif", Typeface.BOLD);

        getWindow().setStatusBarColor(t.windowBg);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        getWindow().getDecorView().setBackgroundColor(t.windowBg);
        findViewById(R.id.root).setBackgroundColor(t.windowBg);

        findViewById(R.id.rowEyebrow).setVisibility(View.GONE);
        ((TextView) findViewById(R.id.tvEyebrow)).setTextColor(t.subColor);
        GradientDrawable sg = new GradientDrawable();
        sg.setColor(t.accent);
        sg.setCornerRadius(2 * d);
        findViewById(R.id.tvSealMini).setBackground(sg);

        tvStatus.setTextColor(t.titleColor);
        tvStatusL.setTextColor(t.titleColor);
        tvStatusL.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        tvSubStatus.setTextColor(t.subColor);
        tvConn.setTextColor(t.subColor);
        tvHint.setTextColor(t.subColor);

        View divTop = findViewById(R.id.divTop);
        divTop.setVisibility(View.VISIBLE);
        divTop.setBackgroundColor(hara ? t.dividerColor : t.dlgLine);
        findViewById(R.id.clockDivider).setBackgroundColor(t.dividerColor);

        // 棋盘左右贴屏: 横向铺满屏幕宽度
        int bm = 0;
        android.widget.LinearLayout.LayoutParams blp = (android.widget.LinearLayout.LayoutParams) boardView.getLayoutParams();
        blp.leftMargin = bm;
        blp.rightMargin = bm;
        boardView.setLayoutParams(blp);
        boardView.setThemeColors(t.boardBg, t.boardLine, t.marker);

        View ped = findViewById(R.id.pedestal);
        if (t.id == UiTheme.MONUMENT) {
            GradientDrawable pg = new GradientDrawable();
            pg.setColor(Color.parseColor("#D8C9B8"));
            pg.setCornerRadius(5 * d);
            ped.setBackground(pg);
            ped.setVisibility(View.VISIBLE);
        } else {
            ped.setVisibility(View.GONE);
        }

        styleButton(t, R.id.btnUndo, "悔棋", "UNDO", t.id == UiTheme.MONUMENT ? 1 : 0);
        styleButton(t, R.id.btnPass, "停一手", "PASS", 0);
        styleButton(t, R.id.btnResign, "认输", "RESIGN", 2);
        styleButton(t, R.id.btnScore, "数子", "COUNT", 0);
        styleButton(t, R.id.btnSave, "存谱", "SAVE SGF", 0);
        styleButton(t, R.id.btnLeave, "离开", "LEAVE", 0);

        findViewById(R.id.tvSign).setVisibility(hara ? View.VISIBLE : View.GONE);
        ((TextView) findViewById(R.id.tvSign)).setTextColor(t.footColor);

        int gridVis = hara ? View.VISIBLE : View.GONE;
        int[] gridLines = {R.id.gridTop, R.id.gridMid, R.id.gridBottom, R.id.vG1, R.id.vG2, R.id.vG3, R.id.vG4};
        for (int id : gridLines) {
            View v = findViewById(id);
            v.setVisibility(gridVis);
            v.setBackgroundColor(t.dividerColor);
        }
        View row1 = findViewById(R.id.row1);
        android.widget.LinearLayout.LayoutParams rlp = (android.widget.LinearLayout.LayoutParams) row1.getLayoutParams();
        rlp.topMargin = (int) ((hara ? 0 : 10) * d);
        row1.setLayoutParams(rlp);
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
            b.setPadding(0, (int) (7 * d), 0, (int) (7 * d));
        } else {
            int m = (int) (6 * d);
            lp.setMargins(m, m, m, m);
            b.setText(zh);
            b.setPadding(0, (int) (6 * d), 0, (int) (6 * d));
        }
        b.setLayoutParams(lp);
        b.setTextColor(t.buttonTextColor(kind));
    }
}
