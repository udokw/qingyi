package com.sijia.go;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.sijia.go.net.GameAdapter;
import com.sijia.go.net.LanHub;

import java.util.ArrayList;
import java.util.List;

/**
 * 象棋联机对局页：棋盘镜像、时钟、悔棋/认输/离开。
 * 规则由房主建房时选定，客机只镜像房主裁决。
 */
public class LanXiangqiActivity extends Activity {
    private UiTheme t;
    private LanHub hub;
    private XiangqiBoardView boardView;
    private TextView tvStatus, tvStatusL, tvConn, tvSubStatus, tvClockR, tvClockB, tvHint, tvEyebrow;

    private int selX = -1, selY = -1;
    private boolean resultShown;
    private SoundPool soundPool;
    private int sndMove, sndCheck, sndWin, sndLose;

    private final LanHub.Listener listener = new LanHub.Listener() {
        @Override
        public void onStateChanged() {
            ui(LanXiangqiActivity.this::render);
        }

        @Override
        public void onMove() {
            ui(() -> {
                playMoveSound();
                render();
            });
        }

        @Override
        public void onClock() {
            ui(LanXiangqiActivity.this::renderClocks);
        }

        @Override
        public void onGameOver(String result, String detail) {
            ui(() -> {
                playGameOverSound(result);
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
            ui(() -> Toast.makeText(LanXiangqiActivity.this, message, Toast.LENGTH_SHORT).show());
        }

        @Override
        public void onError(String message) {
            ui(() -> Toast.makeText(LanXiangqiActivity.this, message, Toast.LENGTH_LONG).show());
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
                android.util.Log.e("LanXiangqiActivity", "界面回调异常", t);
            }
        });
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_lan_xiangqi);
        hub = LanHub.get();
        if (hub.session() == null || !GameAdapter.XIANGQI.equals(hub.gameId())) {
            // 进程被回收后重建时 session 已丢失：直接 finish 会让任务栈变空看起来像"App 退出"
            android.util.Log.w("LanXiangqiActivity", "进入对局页但无会话，直接结束 isTaskRoot=" + isTaskRoot());
            Toast.makeText(this, "对局已结束", Toast.LENGTH_SHORT).show();
            if (isTaskRoot()) startActivity(new android.content.Intent(this, MainActivity.class));
            finish();
            return;
        }
        hub.addListener(listener);

        boardView = findViewById(R.id.boardView);
        tvStatus = findViewById(R.id.tvStatus);
        tvStatusL = findViewById(R.id.tvStatusL);
        tvConn = findViewById(R.id.tvConn);
        tvSubStatus = findViewById(R.id.tvSubStatus);
        tvClockR = findViewById(R.id.tvClockR);
        tvClockB = findViewById(R.id.tvClockB);
        tvHint = findViewById(R.id.tvHint);
        tvEyebrow = findViewById(R.id.tvEyebrow);

        initSounds();
        boardView.setOnPointTapListener(this::onTap);
        boardView.setOnPieceDragListener(this::onDragMove);
        // 坐席视角：黑方玩家整盘旋转 180°, 让黑棋显示在自己这一侧下方
        boardView.setSeatFlipped(hub.myColor() == XiangqiGame.BLACK);

        findViewById(R.id.btnUndo).setOnClickListener(v -> {
            if (!hub.isPlaying()) return;
            if (hub.moveNo() <= 0) {
                Toast.makeText(this, "还没有落子", Toast.LENGTH_SHORT).show();
                return;
            }
            hub.requestUndo();
        });
        findViewById(R.id.btnResign).setOnClickListener(v -> confirmResign());
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

    /**
     * 联机音效：房主裁决后把 move 广播给双方，两端都会收到 onMove，
     * 之前联机路径完全没有调用 SoundPool，所以双方都听不到落子声。
     */
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
        sndWin = soundPool.load(this, R.raw.go_win, 1);
        sndLose = soundPool.load(this, R.raw.go_lose, 1);
    }

    /** 落子声；这一手形成将军时稍后叠加提示音。 */
    private void playMoveSound() {
        if (soundPool == null) return;
        soundPool.play(sndMove, 0.75f, 0.75f, 1, 0, 1f);
        XiangqiGame g = hub.xiangqi();
        if (g != null && !g.isOver() && g.inCheck(g.turn())) {
            boardView.postDelayed(this::playCheckSound, 130);
        }
    }

    private void playCheckSound() {
        if (soundPool != null) soundPool.play(sndCheck, 0.85f, 0.85f, 2, 0, 1f);
    }

    /**
     * 终局音效与围棋一致：按结果码前缀（R 红胜 / B 黑胜 / draw 平局）
     * 加座位判定，赢的喝彩，输的与平局走收束音，不再复用将军音。
     */
    private void playGameOverSound(String result) {
        if (soundPool == null) return;
        boolean win;
        if (result != null && result.startsWith("R")) {
            win = hub.myColor() == XiangqiGame.RED;
        } else if (result != null && result.startsWith("B")) {
            win = hub.myColor() == XiangqiGame.BLACK;
        } else {
            win = false;
        }
        soundPool.play(win ? sndWin : sndLose, 0.85f, 0.85f, 2, 0, 1f);
    }

    @Override
    public void onBackPressed() {
        confirmLeave();
    }

    // ---------------- 落子 ----------------

    private boolean canAct() {
        return hub.isPlaying() && !hub.linkDown() && hub.isMyTurn() && !hub.pendingMove();
    }

    private void onTap(int x, int y) {
        if (!canAct()) return;
        XiangqiGame g = hub.xiangqi();
        if (g == null || g.isOver()) return;
        int p = g.pieceAt(x, y);
        int turn = g.turn();

        // 未选中时直接点对方棋子：若只有一着可吃，直接吃
        if (selX < 0 && p != XiangqiGame.EMPTY && XiangqiGame.colorOf(p) != turn) {
            List<XiangqiGame.Move> caps = capturesTo(x, y, turn, g);
            if (caps.size() == 1) {
                XiangqiGame.Move c = caps.get(0);
                if (tryPlay(c.fx, c.fy, x, y)) return;
            } else if (caps.size() > 1) {
                Toast.makeText(this, "有 " + caps.size() + " 枚棋子可吃，请先选中一枚", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        if (selX >= 0) {
            if (p != XiangqiGame.EMPTY && XiangqiGame.colorOf(p) == turn) {
                select(x, y);
                return;
            }
            tryPlay(selX, selY, x, y);
            select(-1, -1);
            return;
        }
        if (p != XiangqiGame.EMPTY && XiangqiGame.colorOf(p) == turn) select(x, y);
        else Toast.makeText(this, "先选中己方棋子", Toast.LENGTH_SHORT).show();
    }

    private void onDragMove(int fx, int fy, int tx, int ty) {
        if (!canAct()) return;
        XiangqiGame g = hub.xiangqi();
        if (g == null || g.isOver()) return;
        int p = g.pieceAt(fx, fy);
        if (p == XiangqiGame.EMPTY || XiangqiGame.colorOf(p) != g.turn()) {
            onTap(tx, ty);
            return;
        }
        if (tryPlay(fx, fy, tx, ty)) return;
        select(fx, fy);
    }

    private boolean tryPlay(int fx, int fy, int tx, int ty) {
        XiangqiGame g = hub.xiangqi();
        if (g == null) return false;
        boolean legal = false;
        for (XiangqiGame.Move m : g.legalMovesFrom(fx, fy)) {
            if (m.tx == tx && m.ty == ty) {
                legal = true;
                break;
            }
        }
        if (!legal) {
            Toast.makeText(this, g.illegalReason(fx, fy, tx, ty), Toast.LENGTH_SHORT).show();
            return false;
        }
        if (!hub.requestXiangqiMove(fx, fy, tx, ty)) return false;
        select(-1, -1);
        return true;
    }

    private List<XiangqiGame.Move> capturesTo(int tx, int ty, int color, XiangqiGame g) {
        List<XiangqiGame.Move> out = new ArrayList<>();
        for (int y = 0; y < XiangqiGame.ROWS; y++) {
            for (int x = 0; x < XiangqiGame.COLS; x++) {
                int p = g.pieceAt(x, y);
                if (p == XiangqiGame.EMPTY || XiangqiGame.colorOf(p) != color) continue;
                for (XiangqiGame.Move m : g.legalMovesFrom(x, y)) {
                    if (m.tx == tx && m.ty == ty) out.add(m);
                }
            }
        }
        return out;
    }

    private void select(int x, int y) {
        if (x < 0) {
            selX = selY = -1;
            boardView.setSelection(-1, -1);
            boardView.setHints(new ArrayList<>());
            return;
        }
        selX = x;
        selY = y;
        boardView.setSelection(x, y);
        XiangqiGame g = hub.xiangqi();
        // 提示开关是房间规则：客机只能用房主下发的值，不自行决定
        boardView.setHints(g == null || !hub.hintsEnabled()
                ? new ArrayList<>() : g.legalMovesFrom(x, y));
    }

    // ---------------- 渲染 ----------------

    private void render() {
        if (t == null) return;
        XiangqiGame g = hub.xiangqi();
        if (g == null) {
            if (!isFinishing()) finish();
            return;
        }
        boardView.setBoard(g.board());
        GameAdapter.MoveInfo last = hub.lastMove();
        if (last == null) boardView.setLastMove(-1, -1, -1, -1);
        else boardView.setLastMove(last.fx, last.fy, last.tx, last.ty);

        if (selX >= 0) {
            if (!canAct()) select(-1, -1);
            else boardView.setHints(hub.hintsEnabled()
                    ? g.legalMovesFrom(selX, selY) : new ArrayList<>());
        }

        boolean hara = t.hairlineList;
        tvStatusL.setVisibility(View.VISIBLE);
        tvStatusL.setText("我执" + hub.myColorName());
        tvStatus.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        tvStatus.setTypeface(Typeface.create("serif", hara ? Typeface.NORMAL : Typeface.BOLD));

        if (!hub.isPlaying()) {
            tvStatus.setText(hub.state() == LanHub.FINISHED ? "对局结束" : "等待连接");
        } else if (hub.linkDown()) {
            tvStatus.setText("重连中…");
        } else if (hub.pendingMove()) {
            tvStatus.setText("已发送…");
        } else if (hub.isMyTurn()) {
            // 右侧列定宽 76dp：将军提示放前面，保证不被省略号截掉
            tvStatus.setText(g.inCheck(g.turn()) ? "将军 · 轮到你" : "轮到你行棋");
        } else {
            tvStatus.setText(g.inCheck(g.turn()) ? "将军 · 等对手" : "等待对手行棋");
        }

        String role = hub.isHost() ? "房主" : "客人";
        String conn = hub.linkDown() ? "连接中断"
                : (hub.opponentName().equals("对手") ? "等待对手加入" : "已连接");
        tvConn.setText("我执" + hub.myColorName() + " · 对手 " + hub.opponentName());
        ((TextView) findViewById(R.id.tvTitle)).setText("象棋 · 房号 " + hub.roomCode());
        tvSubStatus.setText("9 × 10 · 中国象棋 · " + (hub.timerEnabled() ? "计时" : "不计时"));
        tvEyebrow.setText("联 机 · 第 " + Math.max(1, hub.moveNo()) + " 着 · 房 " + hub.roomCode());

        if (last == null) tvHint.setText("开局 · 红先");
        else tvHint.setText("第 " + hub.moveNo() + " 着 · " + hub.colorName(last.seat) + "方 " + last.text);

        boolean canAct = hub.isPlaying() && !hub.linkDown();
        findViewById(R.id.btnUndo).setEnabled(canAct);
        findViewById(R.id.btnResign).setEnabled(canAct);
        renderClocks();
    }

    private void renderClocks() {
        if (t == null) return;
        int turn = hub.isPlaying() ? hub.turn() : -1;
        boolean hara = t.hairlineList;
        float d = getResources().getDisplayMetrics().density;

        tvClockR.setText(hub.clockLabel(1));
        tvClockB.setText(hub.clockLabel(2));
        boolean rActive = turn == 1;
        boolean bActive = turn == 2;

        if (hara) {
            tvClockR.setBackgroundColor(Color.TRANSPARENT);
            tvClockB.setBackgroundColor(Color.TRANSPARENT);
        } else {
            GradientDrawable bgR = new GradientDrawable();
            bgR.setColor(rActive ? t.primaryBg : t.entryBg[0]);
            bgR.setCornerRadius(18 * d);
            tvClockR.setBackground(bgR);
            GradientDrawable bgB = new GradientDrawable();
            bgB.setColor(bActive ? t.primaryBg : t.entryBg[1]);
            bgB.setCornerRadius(18 * d);
            tvClockB.setBackground(bgB);
            android.view.ViewGroup.MarginLayoutParams lp =
                    (android.view.ViewGroup.MarginLayoutParams) tvClockR.getLayoutParams();
            lp.setMargins((int) (4 * d), 0, (int) (4 * d), 0);
            tvClockR.setLayoutParams(lp);
            android.view.ViewGroup.MarginLayoutParams lp2 =
                    (android.view.ViewGroup.MarginLayoutParams) tvClockB.getLayoutParams();
            lp2.setMargins((int) (4 * d), 0, (int) (4 * d), 0);
            tvClockB.setLayoutParams(lp2);
        }
        tvClockR.setTextColor(rActive ? (hara ? t.accent : t.primaryText) : t.subColor);
        tvClockB.setTextColor(bActive ? (hara ? t.accent : t.primaryText) : t.subColor);
        tvClockR.setTypeface(Typeface.create("serif", rActive ? Typeface.BOLD : Typeface.NORMAL));
        tvClockB.setTypeface(Typeface.create("serif", bActive ? Typeface.BOLD : Typeface.NORMAL));
    }

    // ---------------- 操作 ----------------

    private void askUndo(String who) {
        if (isFinishing() || isDestroyed()) return;
        AlertDialog dlg = UiTheme.themedBuilder(this, t, "悔棋请求")
                .setMessage(who + " 请求悔一着，是否同意？")
                .setNegativeButton("拒绝", (d, w) -> hub.respondUndo(false))
                .setPositiveButton("同意", (d, w) -> hub.respondUndo(true))
                .create();
        dlg.show();
        UiTheme.styleDialog(dlg, t, false);
    }

    private void confirmResign() {
        if (!hub.isPlaying()) return;
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
                .setPositiveButton("返回大厅", (d, w) -> finish())
                .create();
        dlg.show();
        UiTheme.styleDialog(dlg, t, false);
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
        tvEyebrow.setTextColor(t.subColor);
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
        divTop.setBackgroundColor(hara ? t.dividerColor : t.dlgLine);
        findViewById(R.id.clockDivider).setBackgroundColor(t.dividerColor);

        int redPiece = hara ? 0xFFA93226 : 0xFFC0553F;
        int blackPiece = hara ? 0xFF1A1A1A : 0xFF3E3A36;
        boardView.setThemeColors(t.boardBg, t.boardLine, redPiece, blackPiece, t.marker);

        styleButton(t, R.id.btnUndo, "悔棋", "UNDO", 0);
        styleButton(t, R.id.btnResign, "认输", "RESIGN", 2);
        styleButton(t, R.id.btnLeave, "离开", "LEAVE", 0);

        findViewById(R.id.tvSign).setVisibility(hara ? View.VISIBLE : View.GONE);
        ((TextView) findViewById(R.id.tvSign)).setTextColor(t.footColor);

        int gridVis = hara ? View.VISIBLE : View.GONE;
        int[] gridLines = {R.id.gridTop, R.id.gridBottom, R.id.vG1, R.id.vG2};
        for (int id : gridLines) {
            View v = findViewById(id);
            v.setVisibility(gridVis);
            v.setBackgroundColor(t.dividerColor);
        }
        View row1 = findViewById(R.id.row1);
        android.widget.LinearLayout.LayoutParams rlp =
                (android.widget.LinearLayout.LayoutParams) row1.getLayoutParams();
        rlp.topMargin = (int) ((hara ? 0 : 8) * d);
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
