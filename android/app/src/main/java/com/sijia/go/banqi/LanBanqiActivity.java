package com.sijia.go.banqi;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;

import com.sijia.go.MainActivity;
import com.sijia.go.R;
import com.sijia.go.UiTheme;
import com.sijia.go.net.GameAdapter;
import com.sijia.go.net.LanHub;

/**
 * 暗棋联机对局页。
 * 网络层复用 LanHub（房号 / 发现 / 握手 / 重连 / 房主权威），暗子身份只由房主掌握。
 * 常驻 HUD 只有轮次与红/黑/暗计数，操作收在右侧浮层。
 */
public class LanBanqiActivity extends ComponentActivity {
    private UiTheme t;
    private LanHub hub;
    private BanqiBoardView boardView;
    private TextView tvTurn, tvCount, tvPanelTitle, btnPanelClose, btnMore;
    private TextView rowUndo, rowResign, rowHome, tvRoomValue;
    private LinearLayout panel, panelHead, rowRoom;
    private View scrim, divHead, divUndo, divResign, divRoom, divHome;

    private int selX = -1;
    private int selY = -1;
    private boolean panelOpen;
    private boolean exitDialogShowing;
    private boolean resultShown;
    /** 落子音效，与象棋同一份资源；用 moveNo 判断是否真的新增了一手（悔棋不回放）。 */
    private SoundPool soundPool;
    private int sndMove;
    private int lastSoundMoveNo = -1;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final LanHub.Listener listener = new LanHub.Listener() {
        @Override
        public void onStateChanged() {
            ui(LanBanqiActivity.this::render);
        }

        @Override
        public void onGameStart() {
            ui(() -> {
                lastSoundMoveNo = hub.moveNo();
                clearSelection();
                render();
            });
        }

        @Override
        public void onMove() {
            ui(() -> {
                int moveNo = hub.moveNo();
                if (hub.isPlaying() && moveNo > lastSoundMoveNo) {
                    playMoveSound();
                }
                lastSoundMoveNo = moveNo;
                clearSelection();
                render();
            });
        }

        @Override
        public void onGameOver(String result, String detail) {
            ui(() -> {
                render();
                showResult();
            });
        }

        @Override
        public void onUndoRequest(String requester) {
            ui(() -> {
                clearSelection();
                askUndo(requester);
            });
        }

        @Override
        public void onNotice(String message) {
            ui(() -> Toast.makeText(LanBanqiActivity.this, message, Toast.LENGTH_SHORT).show());
        }

        @Override
        public void onError(String message) {
            ui(() -> Toast.makeText(LanBanqiActivity.this, message, Toast.LENGTH_LONG).show());
        }
    };

    private void ui(Runnable action) {
        if (isFinishing() || isDestroyed()) return;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            action.run();
        });
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_lan_banqi);
        hub = LanHub.get();
        if (hub.session() == null || !GameAdapter.BANQI.equals(hub.gameId())) {
            Toast.makeText(this, "对局已结束", Toast.LENGTH_SHORT).show();
            if (isTaskRoot()) startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }
        hub.addListener(listener);
        initSounds();
        lastSoundMoveNo = hub.moveNo();

        boardView = findViewById(R.id.boardView);
        tvTurn = findViewById(R.id.tvTurn);
        tvCount = findViewById(R.id.tvCount);
        tvPanelTitle = findViewById(R.id.tvPanelTitle);
        btnPanelClose = findViewById(R.id.btnPanelClose);
        btnMore = findViewById(R.id.btnMore);
        rowUndo = findViewById(R.id.rowUndo);
        rowResign = findViewById(R.id.rowResign);
        rowHome = findViewById(R.id.rowHome);
        tvRoomValue = findViewById(R.id.tvRoomValue);
        panel = findViewById(R.id.panel);
        panelHead = findViewById(R.id.panelHead);
        rowRoom = findViewById(R.id.rowRoom);
        scrim = findViewById(R.id.scrim);
        divHead = findViewById(R.id.divHead);
        divUndo = findViewById(R.id.divUndo);
        divResign = findViewById(R.id.divResign);
        divRoom = findViewById(R.id.divRoom);
        divHome = findViewById(R.id.divHome);

        boardView.setOnCellTapListener(new BanqiBoardView.OnCellTapListener() {
            @Override
            public void onCellTap(int x, int y) {
                onTap(x, y);
            }

            @Override
            public void onBoardLongPress() {
                openPanel();
            }
        });

        btnMore.setOnClickListener(v -> openPanel());
        btnPanelClose.setOnClickListener(v -> closePanel());
        scrim.setOnClickListener(v -> closePanel());
        rowUndo.setOnClickListener(v -> {
            closePanel();
            onUndo();
        });
        rowResign.setOnClickListener(v -> {
            closePanel();
            confirmResign();
        });
        rowHome.setOnClickListener(v -> {
            closePanel();
            confirmHome();
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (panelOpen) closePanel();
                else openPanel();
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
        handler.removeCallbacksAndMessages(null);
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
        super.onDestroy();
        hub.removeListener(listener);
        if (isFinishing()) hub.stop();
    }

    // ------------------------------------------------------------------
    // 音效（与象棋同一份资源、同一套 SoundPool 播放入口）
    // ------------------------------------------------------------------

    private void initSounds() {
        soundPool = new SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        sndMove = soundPool.load(this, R.raw.xq_move, 1);
    }

    /** 双方都由房主统一广播，收到真正的新一手时播放。 */
    private void playMoveSound() {
        if (soundPool != null) soundPool.play(sndMove, 0.75f, 0.75f, 1, 0, 1f);
    }

    // ------------------------------------------------------------------
    // 交互
    // ------------------------------------------------------------------

    private boolean canAct() {
        return hub.isPlaying() && hub.isMyTurn() && !hub.linkDown() && !hub.pendingMove();
    }

    private void onTap(int x, int y) {
        if (!hub.isPlaying()) {
            toast("对局尚未开始或已结束");
            return;
        }
        if (hub.linkDown()) {
            toast("连接中断，正在重连");
            return;
        }
        if (!hub.isMyTurn()) {
            toast("还没轮到你走棋");
            return;
        }
        if (hub.pendingMove()) {
            toast("上一手还在确认中");
            return;
        }
        BanqiGame g = hub.banqi();
        if (g == null) return;

        if (g.isHiddenAt(x, y)) {
            clearSelection();
            if (!hub.requestBanqiFlip(x, y)) toast("这一手没能发出");
            return;
        }
        if (!g.hasPieceAt(x, y)) {
            if (selX >= 0) {
                if (tryMove(x, y)) return;
                toast("这步不合规则");
            }
            clearSelection();
            return;
        }
        if (BanqiGame.colorOf(g.pieceCodeAt(x, y)) == g.turnColor()) {
            select(x, y);
            return;
        }
        if (selX >= 0) {
            if (tryMove(x, y)) return;
            toast("不能吃这枚子");
            clearSelection();
            return;
        }
        toast("请先选中己方明子");
    }

    private void select(int x, int y) {
        BanqiGame g = hub.banqi();
        if (g == null) return;
        selX = x;
        selY = y;
        boardView.setSelected(x, y);
        boardView.setLegalTargets(g.legalMovesFrom(x, y));
    }

    private void clearSelection() {
        selX = -1;
        selY = -1;
        if (boardView != null) boardView.clearSelection();
    }

    private boolean tryMove(int tx, int ty) {
        BanqiGame g = hub.banqi();
        if (g == null || selX < 0 || selY < 0) return false;
        boolean legal = false;
        for (BanqiGame.Move m : g.legalMovesFrom(selX, selY)) {
            if (m.tx == tx && m.ty == ty) {
                legal = true;
                break;
            }
        }
        if (!legal) return false;
        if (!hub.requestBanqiMove(selX, selY, tx, ty)) return false;
        clearSelection();
        return true;
    }

    // ------------------------------------------------------------------
    // 面板
    // ------------------------------------------------------------------

    private void onUndo() {
        if (!hub.isPlaying()) return;
        if (hub.moveNo() <= 0) {
            toast("还没有落子");
            return;
        }
        hub.requestUndo();
    }

    private void askUndo(String who) {
        if (isFinishing() || isDestroyed()) return;
        UiTheme theme = t != null ? t : UiTheme.current(this);
        AlertDialog dlg = UiTheme.themedBuilder(this, theme, "悔棋请求")
                .setMessage(who + " 请求悔一手，是否同意？")
                .setNegativeButton("拒绝", (d, w) -> hub.respondUndo(false))
                .setPositiveButton("同意", (d, w) -> hub.respondUndo(true))
                .create();
        dlg.show();
        UiTheme.styleDialog(dlg, theme, false);
    }

    private void confirmResign() {
        if (!hub.isPlaying()) return;
        UiTheme theme = t != null ? t : UiTheme.current(this);
        AlertDialog dlg = UiTheme.themedBuilder(this, theme, "认输")
                .setMessage("确认认输？本局将判负。")
                .setNegativeButton("再想想", null)
                .setPositiveButton("认输", (d, w) -> hub.resign())
                .create();
        dlg.show();
        UiTheme.styleDialog(dlg, theme, true);
    }

    /** 返回主页确认：自定义圆角卡片；离开时统一 stop 会话，避免房间挂在后台。 */
    private void confirmHome() {
        if (exitDialogShowing || isFinishing()) return;
        UiTheme theme = t != null ? t : UiTheme.current(this);
        float d = getResources().getDisplayMetrics().density;
        View v = getLayoutInflater().inflate(R.layout.dialog_banqi, null);
        TextView title = v.findViewById(R.id.tvDlgTitle);
        TextView msg = v.findViewById(R.id.tvDlgMessage);
        View mark = v.findViewById(R.id.tvDlgMark);
        View div = v.findViewById(R.id.divDlg);
        Button cancel = v.findViewById(R.id.btnCancel);
        Button end = v.findViewById(R.id.btnEnd);

        title.setTextColor(theme.titleColor);
        title.setTypeface(Typeface.create("serif", Typeface.BOLD));
        title.setLetterSpacing(0.08f);
        GradientDrawable mk = new GradientDrawable();
        mk.setColor(theme.accent);
        if (theme.hairlineList) mk.setCornerRadius(2 * d);
        else mk.setShape(GradientDrawable.OVAL);
        mark.setBackground(mk);
        div.setBackgroundColor(theme.dlgLine);
        msg.setTextColor(theme.subColor);
        msg.setText("结束本局并返回主页?");
        styleDialogButton(cancel, theme, 0);
        styleDialogButton(end, theme, 2);

        final AlertDialog dlg = new AlertDialog.Builder(this).setView(v).create();
        cancel.setOnClickListener(x -> dlg.dismiss());
        end.setOnClickListener(x -> {
            dlg.dismiss();
            hub.stop();
            finish();
        });
        dlg.setOnDismissListener(x -> exitDialogShowing = false);
        exitDialogShowing = true;
        dlg.show();
        if (dlg.getWindow() != null) dlg.getWindow().setBackgroundDrawable(theme.dialogBackground());
    }

    private void styleDialogButton(Button b, UiTheme theme, int kind) {
        float d = getResources().getDisplayMetrics().density;
        b.setBackground(theme.buttonDrawable(kind));
        b.setTextColor(theme.buttonTextColor(kind));
        b.setTypeface(Typeface.create(theme.hairlineList ? "serif" : "sans-serif", Typeface.NORMAL));
        b.setPadding(0, (int) (10 * d), 0, (int) (10 * d));
    }

    private void openPanel() {
        if (panelOpen) return;
        panelOpen = true;
        tvRoomValue.setText(hub.roomCode());
        scrim.setVisibility(View.VISIBLE);
        panel.setVisibility(View.VISIBLE);
    }

    private void closePanel() {
        if (!panelOpen) return;
        panelOpen = false;
        scrim.setVisibility(View.GONE);
        panel.setVisibility(View.GONE);
    }

    private void showResult() {
        if (resultShown || isFinishing()) return;
        resultShown = true;
        UiTheme theme = t != null ? t : UiTheme.current(this);
        AlertDialog dlg = UiTheme.themedBuilder(this, theme, "对局结束")
                .setMessage(hub.resultText())
                .setNegativeButton("查看棋盘", null)
                .setPositiveButton("离开", (d, w) -> {
                    hub.stop();
                    finish();
                })
                .create();
        dlg.show();
        UiTheme.styleDialog(dlg, theme, false);
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    private void render() {
        if (t == null) return;
        BanqiGame g = hub.banqi();
        if (g == null) {
            if (!isFinishing()) finish();
            return;
        }
        boardView.setGame(g);
        boardView.setSeatPerspective(hub.myColor());
        boolean canAct = canAct();
        boardView.setInteractive(canAct);
        if (selX >= 0 && selY >= 0 && canAct && g.isFaceUp(selX, selY)) {
            boardView.setSelected(selX, selY);
            boardView.setLegalTargets(g.legalMovesFrom(selX, selY));
        } else {
            clearSelection();
        }
        refreshHud(g);
        tvRoomValue.setText(hub.roomCode());
        if (hub.state() == LanHub.FINISHED && !hub.resultDetail().isEmpty()) showResult();
    }

    private void refreshHud(BanqiGame g) {
        String phase;
        if (!hub.isPlaying()) {
            phase = hub.state() == LanHub.FINISHED ? "对局结束" : "等待连接";
        } else if (hub.linkDown()) {
            phase = "重连中";
        } else if (!hub.isMyTurn()) {
            phase = "等待对手";
        } else {
            phase = hub.pendingMove() ? "已发送" : "轮到你";
        }

        String text;
        int dotColor;
        if (hub.isPlaying() && !hub.linkDown()) {
            int color = g.turnColor();
            String colorPart = color == BanqiGame.RED ? "红方行棋"
                    : color == BanqiGame.BLACK ? "黑方行棋"
                    : (g.turnSeat() == BanqiGame.SEAT_FIRST ? "先手行棋" : "后手行棋");
            text = "● " + phase + " · " + colorPart;
            dotColor = sideColor(color);
        } else {
            text = "● " + phase;
            dotColor = t != null ? t.subColor : Color.GRAY;
        }
        SpannableString s = new SpannableString(text);
        s.setSpan(new ForegroundColorSpan(dotColor), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new ForegroundColorSpan(t != null ? t.titleColor : Color.BLACK),
                2, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tvTurn.setText(s);

        int red = 0;
        int black = 0;
        int hidden = 0;
        for (int y = 0; y < BanqiGame.ROWS; y++) {
            for (int x = 0; x < BanqiGame.COLS; x++) {
                int p = g.pieceCodeAt(x, y);
                if (p == BanqiGame.EMPTY) continue;
                if (!g.isFaceUp(x, y)) {
                    hidden++;
                } else if (BanqiGame.colorOf(p) == BanqiGame.RED) {
                    red++;
                } else {
                    black++;
                }
            }
        }
        tvCount.setText("红 " + red + " · 黑 " + black + " · 暗 " + hidden);
    }

    private int sideColor(int color) {
        boolean hara = t != null && t.hairlineList;
        if (color == BanqiGame.RED) return hara ? 0xFFA93226 : 0xFFC0553F;
        if (color == BanqiGame.BLACK) return hara ? 0xFF1A1A1A : 0xFF3E3A36;
        return t != null ? t.subColor : Color.GRAY;
    }

    // ------------------------------------------------------------------
    // 主题
    // ------------------------------------------------------------------

    private void applyTheme(UiTheme theme) {
        t = theme;
        boolean hara = theme.hairlineList;
        float d = getResources().getDisplayMetrics().density;

        getWindow().setStatusBarColor(theme.windowBg);
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        decor.setBackgroundColor(theme.windowBg);
        findViewById(R.id.root).setBackgroundColor(theme.windowBg);

        tvTurn.setTextColor(theme.titleColor);
        tvTurn.setTypeface(Typeface.create("serif", hara ? Typeface.NORMAL : Typeface.BOLD));
        tvCount.setTextColor(theme.subColor);
        btnMore.setTextColor(Color.argb(150, Color.red(theme.titleColor),
                Color.green(theme.titleColor), Color.blue(theme.titleColor)));

        int redPiece = 0xFFA93226;
        int blackPiece = 0xFF1A1A1A;
        boardView.setThemeColors(theme.boardBg, theme.boardLine, redPiece, blackPiece,
                theme.accent, theme.marker);
        boardView.setSeatPerspective(hub != null ? hub.myColor() : 0);

        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(theme.windowBg);
        panelBg.setCornerRadius((hara ? 6 : 20) * d);
        panel.setBackground(panelBg);
        int pad = hara ? 0 : (int) (10 * d);
        panel.setPadding(pad, pad, pad, pad);

        LinearLayout.LayoutParams hp = (LinearLayout.LayoutParams) panelHead.getLayoutParams();
        hp.height = (int) ((hara ? 52 : 42) * d);
        panelHead.setLayoutParams(hp);
        int hpad = (int) ((hara ? 20 : 8) * d);
        panelHead.setPadding(hpad, 0, hpad, 0);

        tvPanelTitle.setTextColor(theme.subColor);
        btnPanelClose.setTextColor(theme.subColor);

        styleDivider(divHead, hara, theme);
        styleDivider(divUndo, hara, theme);
        styleDivider(divResign, hara, theme);
        styleDivider(divRoom, hara, theme);
        styleDivider(divHome, hara, theme);

        styleRow(rowUndo, false, theme);
        styleRow(rowResign, true, theme);
        styleRow(rowHome, true, theme);
        styleRoomRow(theme);
    }

    private void styleDivider(View v, boolean hara, UiTheme theme) {
        v.setVisibility(hara ? View.VISIBLE : View.GONE);
        v.setBackgroundColor(theme.dividerColor);
    }

    private void styleRow(TextView row, boolean danger, UiTheme theme) {
        boolean hara = theme.hairlineList;
        float d = getResources().getDisplayMetrics().density;
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) row.getLayoutParams();
        lp.height = (int) ((hara ? 54 : 48) * d);
        if (hara) {
            lp.setMargins(0, 0, 0, 0);
            row.setBackgroundColor(Color.TRANSPARENT);
            row.setPadding((int) (20 * d), 0, (int) (20 * d), 0);
        } else {
            int m = (int) (3.5f * d);
            lp.setMargins(0, m, 0, m);
            GradientDrawable g = new GradientDrawable();
            g.setColor(theme.btnBg);
            g.setCornerRadius(14 * d);
            row.setBackground(g);
            row.setPadding((int) (16 * d), 0, (int) (16 * d), 0);
        }
        row.setLayoutParams(lp);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setTextSize(hara ? 15 : 14);
        row.setTypeface(Typeface.create(hara ? "serif" : "sans-serif", Typeface.NORMAL));
        row.setLetterSpacing(hara ? 0.14f : 0.1f);
        row.setTextColor(danger ? theme.dangerText : theme.titleColor);
    }

    private void styleRoomRow(UiTheme theme) {
        boolean hara = theme.hairlineList;
        float d = getResources().getDisplayMetrics().density;
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) rowRoom.getLayoutParams();
        lp.height = (int) ((hara ? 54 : 48) * d);
        if (hara) {
            lp.setMargins(0, 0, 0, 0);
            rowRoom.setBackgroundColor(Color.TRANSPARENT);
            rowRoom.setPadding((int) (20 * d), 0, (int) (20 * d), 0);
        } else {
            int m = (int) (3.5f * d);
            lp.setMargins(0, m, 0, m);
            GradientDrawable g = new GradientDrawable();
            g.setColor(theme.btnBg);
            g.setCornerRadius(14 * d);
            rowRoom.setBackground(g);
            rowRoom.setPadding((int) (16 * d), 0, (int) (16 * d), 0);
        }
        rowRoom.setLayoutParams(lp);

        TextView label = findViewById(R.id.tvRoomLabel);
        label.setTextSize(hara ? 15 : 14);
        label.setTypeface(Typeface.create(hara ? "serif" : "sans-serif", Typeface.NORMAL));
        label.setLetterSpacing(hara ? 0.14f : 0.1f);
        label.setTextColor(theme.titleColor);

        tvRoomValue.setTextColor(hara ? theme.footColor : theme.subColor);
        tvRoomValue.setTextSize(10.5f);
        tvRoomValue.setText(hub != null ? hub.roomCode() : "—");
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
