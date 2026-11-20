package com.sijia.go.banqi;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * 暗棋棋盘 View：4 行 × 8 列铺满整宽，暗子只画背面，明子按红黑着色。
 * 棋盘线、装饰内框、选中/可走提示的数值与 XiangqiBoardView 保持一致。
 * 座位 2 可将整盘 180° 翻转显示，触摸回调始终返回逻辑坐标。
 */
public class BanqiBoardView extends View {

    public interface OnCellTapListener {
        void onCellTap(int x, int y);

        void onBoardLongPress();
    }

    private static final int FACE_FILL = Color.parseColor("#F6EBD8");
    private static final long LONG_PRESS_MS = 300L;
    /** 暗子背面的细纹理方向（单位向量）。 */
    private static final float[] TEXTURE = {
            1f, 0f, 0.7071f, 0.7071f, 0f, 1f, -0.7071f, 0.7071f,
            -1f, 0f, -0.7071f, -0.7071f, 0f, -1f, 0.7071f, -0.7071f
    };

    private BanqiGame game;
    private boolean interactive = true;
    private boolean flipped;
    private int selX = -1;
    private int selY = -1;
    private List<BanqiGame.Move> legalTargets = new ArrayList<BanqiGame.Move>();
    private OnCellTapListener listener;

    private int boardBg = Color.parseColor("#E3B771");
    private int lineColor = Color.parseColor("#3A3226");
    private int redColor = Color.parseColor("#A93226");
    private int blackColor = Color.parseColor("#1A1A1A");
    private int accentColor = Color.parseColor("#C73E2B");
    private int markerColor = Color.parseColor("#C73E2B");

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private float cell;
    private float originX;
    private float originY;

    private int hitX = -1;
    private int hitY = -1;
    private int downX = -1;
    private int downY = -1;
    private float downRawX;
    private float downRawY;
    private boolean longPressFired;
    private int touchSlop;

    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            longPressFired = true;
            if (listener != null) listener.onBoardLongPress();
        }
    };

    public BanqiBoardView(Context c) {
        super(c);
        init();
    }

    public BanqiBoardView(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    private void init() {
        strokePaint.setStyle(Paint.Style.STROKE);
        textPaint.setTypeface(Typeface.create("serif", Typeface.BOLD));
        textPaint.setTextAlign(Paint.Align.CENTER);
        touchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        setClickable(true);
    }

    // ------------------------------------------------------------------
    // 外部接口
    // ------------------------------------------------------------------

    public void setGame(BanqiGame g) {
        game = g;
        invalidate();
    }

    public void setInteractive(boolean enabled) {
        interactive = enabled;
    }

    /** 座位 2 且规则允许时整盘 180° 翻转；回调坐标不受影响。 */
    public void setSeatPerspective(int seat) {
        flipped = seat == BanqiGame.SEAT_SECOND && BanqiOptions.flipForSeat2;
        invalidate();
    }

    public void setThemeColors(int bg, int lineC, int red, int black, int accentC, int markerC) {
        boardBg = bg;
        lineColor = lineC;
        redColor = red;
        blackColor = black;
        accentColor = accentC;
        markerColor = markerC;
        invalidate();
    }

    public void setOnCellTapListener(OnCellTapListener l) {
        listener = l;
    }

    public void setSelected(int x, int y) {
        selX = x;
        selY = y;
        invalidate();
    }

    public void setLegalTargets(List<BanqiGame.Move> moves) {
        legalTargets = moves == null ? new ArrayList<BanqiGame.Move>() : moves;
        invalidate();
    }

    public void clearSelection() {
        selX = -1;
        selY = -1;
        legalTargets = new ArrayList<BanqiGame.Move>();
        invalidate();
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = MeasureSpec.getMode(hSpec) == MeasureSpec.UNSPECIFIED
                ? Math.round(w * 0.55f) : MeasureSpec.getSize(hSpec);
        setMeasuredDimension(w, h);
    }

    /**
     * 自适应几何：格子取 8 列 / 4 行可用空间的较小者，保证正方形；
     * 棋盘在可用区域内居中，上下左右留白对称。顶部让出 HUD，底部让出 ⋯ 入口。
     */
    private void computeGeometry(int w, int h) {
        float side = dp(10f);
        float top = dp(46f);
        float bottom = dp(46f);
        // 空间过窄时按比例收缩上下留白，避免小屏棋盘被挤得太小
        float reserveV = top + bottom;
        if (reserveV > h * 0.34f) {
            top = h * 0.17f;
            bottom = h * 0.17f;
        }
        float aw = Math.max(1f, w - 2f * side);
        float ah = Math.max(1f, h - top - bottom);
        // 0.92：给装饰内框（外扩 0.28 格）与描边留出余量
        float cellX = aw * 0.92f / BanqiGame.COLS;
        float cellY = ah * 0.92f / BanqiGame.ROWS;
        cell = Math.min(cellX, cellY);
        originX = side + (aw - cell * BanqiGame.COLS) / 2f;
        originY = top + (ah - cell * BanqiGame.ROWS) / 2f;
    }

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        computeGeometry(w, h);
        cv.drawColor(boardBg);

        float left = originX;
        float top = originY;
        float right = originX + cell * BanqiGame.COLS;
        float bottom = originY + cell * BanqiGame.ROWS;

        // 装饰内框：外扩 0.28 格、圆角 3dp、1.4dp 线、alpha 140
        float off = cell * 0.28f;
        float rad = dp(3f);
        strokePaint.setStrokeWidth(dp(1.4f));
        strokePaint.setColor(lineColor);
        strokePaint.setAlpha(140);
        rect.set(left - off, top - off, right + off, bottom + off);
        cv.drawRoundRect(rect, rad, rad, strokePaint);

        // 棋盘外描边：1dp、alpha 179、贴画布边缘
        float sw = dp(1f);
        strokePaint.setStrokeWidth(sw);
        strokePaint.setAlpha(179);
        float fi = sw / 2f + 1f;
        rect.set(fi, fi, w - fi, h - fi);
        cv.drawRect(rect, strokePaint);
        strokePaint.setAlpha(255);

        // 格线 1.6dp
        strokePaint.setColor(lineColor);
        strokePaint.setStrokeWidth(dp(1.6f));
        for (int r = 0; r <= BanqiGame.ROWS; r++) {
            float y = top + r * cell;
            cv.drawLine(left, y, right, y, strokePaint);
        }
        for (int c = 0; c <= BanqiGame.COLS; c++) {
            float x = left + c * cell;
            cv.drawLine(x, top, x, bottom, strokePaint);
        }
        // 外框加粗 3.2dp
        strokePaint.setStrokeWidth(dp(3.2f));
        rect.set(left, top, right, bottom);
        cv.drawRect(rect, strokePaint);

        if (game != null) {
            for (int y = 0; y < BanqiGame.ROWS; y++) {
                for (int x = 0; x < BanqiGame.COLS; x++) {
                    int p = game.pieceCodeAt(x, y);
                    if (p == BanqiGame.EMPTY) continue;
                    drawPiece(cv, px(x), py(y), p, game.isFaceUp(x, y));
                }
            }
        }

        drawSelection(cv);
    }

    private void drawPiece(Canvas cv, float cx, float cy, int p, boolean faceUp) {
        float r = cell * 0.425f;
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(FACE_FILL);
        fillPaint.setAlpha(255);
        cv.drawCircle(cx, cy, r, fillPaint);

        if (!faceUp) {
            // 暗子：只画背面，不泄漏任何身份
            strokePaint.setColor(lineColor);
            strokePaint.setAlpha(200);
            strokePaint.setStrokeWidth(dp(2.2f));
            cv.drawCircle(cx, cy, r, strokePaint);
            strokePaint.setAlpha(70);
            strokePaint.setStrokeWidth(dp(1f));
            cv.drawCircle(cx, cy, r * 0.82f, strokePaint);
            strokePaint.setAlpha(45);
            strokePaint.setStrokeWidth(dp(0.9f));
            for (int i = 0; i < TEXTURE.length; i += 2) {
                float ux = TEXTURE[i];
                float uy = TEXTURE[i + 1];
                cv.drawLine(cx + ux * r * 0.38f, cy + uy * r * 0.38f,
                        cx + ux * r * 0.62f, cy + uy * r * 0.62f, strokePaint);
            }
            strokePaint.setAlpha(255);
            return;
        }

        int ink = BanqiGame.colorOf(p) == BanqiGame.RED ? redColor : blackColor;
        int glyph = BanqiGame.colorOf(p) == BanqiGame.RED ? 0xFFC0553F : 0xFF3E3A36;
        strokePaint.setColor(ink);
        strokePaint.setAlpha(255);
        strokePaint.setStrokeWidth(dp(2.2f));
        cv.drawCircle(cx, cy, r, strokePaint);
        strokePaint.setAlpha(70);
        strokePaint.setStrokeWidth(dp(1f));
        cv.drawCircle(cx, cy, r * 0.82f, strokePaint);
        strokePaint.setAlpha(255);

        textPaint.setColor(glyph);
        textPaint.setTextSize(cell * 0.42f);
        float ty = cy - (textPaint.ascent() + textPaint.descent()) / 2f;
        cv.drawText(BanqiGame.pieceName(p), cx, ty, textPaint);
    }

    private void drawSelection(Canvas cv) {
        float r = cell * 0.425f;
        if (selX >= 0 && selY >= 0) {
            strokePaint.setColor(accentColor);
            strokePaint.setAlpha(255);
            strokePaint.setStrokeWidth(dp(3.2f));
            cv.drawCircle(px(selX), py(selY), r + cell * 0.06f, strokePaint);
        }
        for (int i = 0; i < legalTargets.size(); i++) {
            BanqiGame.Move m = legalTargets.get(i);
            if (m.flip) continue;
            boolean occupied = game != null && game.hasPieceAt(m.tx, m.ty);
            if (occupied) {
                strokePaint.setColor(accentColor);
                strokePaint.setAlpha(255);
                strokePaint.setStrokeWidth(dp(3f));
                cv.drawCircle(px(m.tx), py(m.ty), cell * 0.48f, strokePaint);
            } else {
                fillPaint.setStyle(Paint.Style.FILL);
                fillPaint.setColor(markerColor);
                fillPaint.setAlpha(110);
                cv.drawCircle(px(m.tx), py(m.ty), cell * 0.14f, fillPaint);
            }
        }
        fillPaint.setAlpha(255);
    }

    private float px(int x) {
        int sx = flipped ? BanqiGame.COLS - 1 - x : x;
        return originX + (sx + 0.5f) * cell;
    }

    private float py(int y) {
        int sy = flipped ? BanqiGame.ROWS - 1 - y : y;
        return originY + (sy + 0.5f) * cell;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    // ------------------------------------------------------------------
    // 触摸
    // ------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (listener == null) return super.onTouchEvent(e);
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            removeCallbacks(longPressRunnable);
            longPressFired = false;
            downX = downY = -1;
            downRawX = e.getX();
            downRawY = e.getY();
            if (cellAt(downRawX, downRawY)) {
                downX = hitX;
                downY = hitY;
            }
            // 长按棋盘任意位置都能呼出面板（含四周留白）
            postDelayed(longPressRunnable, LONG_PRESS_MS);
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (Math.abs(e.getX() - downRawX) > touchSlop
                    || Math.abs(e.getY() - downRawY) > touchSlop) {
                removeCallbacks(longPressRunnable);
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            removeCallbacks(longPressRunnable);
            if (downX >= 0 && !longPressFired && interactive
                    && cellAt(e.getX(), e.getY())
                    && hitX == downX && hitY == downY) {
                listener.onCellTap(hitX, hitY);
            }
            downX = downY = -1;
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            removeCallbacks(longPressRunnable);
            downX = downY = -1;
        }
        return true;
    }

    /** 用与绘制完全相同的一套 origin/cell 定位，留白区域不响应。 */
    private boolean cellAt(float ex, float ey) {
        if (cell <= 0) return false;
        int sx = (int) Math.floor((ex - originX) / cell);
        int sy = (int) Math.floor((ey - originY) / cell);
        if (sx < 0 || sx >= BanqiGame.COLS || sy < 0 || sy >= BanqiGame.ROWS) {
            return false;
        }
        hitX = flipped ? BanqiGame.COLS - 1 - sx : sx;
        hitY = flipped ? BanqiGame.ROWS - 1 - sy : sy;
        return true;
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(longPressRunnable);
        super.onDetachedFromWindow();
    }
}
