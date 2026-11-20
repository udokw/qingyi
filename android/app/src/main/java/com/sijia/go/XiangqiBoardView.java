package com.sijia.go;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 中国象棋棋盘 View: 木质底色 + 楚河汉界 + 九宫斜线 + 兵/炮位标记,
 * 棋子为扁平玉质圆牌, 支持选中高亮与合法落点提示.
 */
public class XiangqiBoardView extends View {
    public interface OnPointTapListener { void onPointTap(int x, int y); }
    public interface OnPieceDragListener { void onDrag(int fx, int fy, int tx, int ty); }

    private int[][] board = new int[XiangqiGame.ROWS][XiangqiGame.COLS];
    private List<XiangqiGame.Move> hints = new ArrayList<>();
    private int selX = -1, selY = -1;
    private int lastFx = -1, lastFy = -1, lastTx = -1, lastTy = -1;
    private OnPointTapListener listener;
    private OnPieceDragListener dragListener;
    private int downX = -1, downY = -1;
    private boolean flipBlackText = false;
    /** 坐席视角：黑方玩家整盘旋转 180°, 让己方棋子靠近底部。 */
    private boolean seatFlipped = false;

    private int boardBg = Color.parseColor("#E3B771");
    private int lineColor = Color.parseColor("#3A3226");
    private int redColor = Color.parseColor("#B23A2A");
    private int blackColor = Color.parseColor("#1A1A1A");
    private int accent = Color.parseColor("#C73E2B");

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float margin, cell;

    public XiangqiBoardView(Context c) { super(c); init(); }
    public XiangqiBoardView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        text.setTypeface(Typeface.create("serif", Typeface.BOLD));
        text.setTextAlign(Paint.Align.CENTER);
    }

    public void setThemeColors(int bg, int lineC, int red, int black, int accentC) {
        boardBg = bg;
        lineColor = lineC;
        redColor = red;
        blackColor = black;
        accent = accentC;
        invalidate();
    }

    public void setOnPointTapListener(OnPointTapListener l) { listener = l; }
    public void setOnPieceDragListener(OnPieceDragListener l) { dragListener = l; }

    public void setBoard(int[][] b) {
        board = b;
        invalidate();
    }

    /** 双人对局时黑方棋子文字倒置, 人机对局保持同向便于观棋 */
    public void setFlipBlackText(boolean flip) {
        flipBlackText = flip;
        invalidate();
    }

    /**
     * 坐席视角：整盘 180° 翻转（棋盘线、九宫、兵炮位、楚河汉界、落点提示、最后一步一起翻）,
     * 棋子文字保持正向可读。与 {@link #setFlipBlackText(boolean)} 互斥。
     */
    public void setSeatFlipped(boolean flip) {
        seatFlipped = flip;
        flipBlackText = false;
        invalidate();
    }

    public boolean isSeatFlipped() {
        return seatFlipped;
    }

    public void setSelection(int x, int y) { selX = x; selY = y; invalidate(); }

    public void setHints(List<XiangqiGame.Move> moves) {
        hints = moves == null ? new ArrayList<>() : moves;
        invalidate();
    }

    public void setLastMove(int fx, int fy, int tx, int ty) {
        lastFx = fx; lastFy = fy; lastTx = tx; lastTy = ty;
        invalidate();
    }

    /** 棋盘的 高/宽 比例: 高 = 2*0.05w + 9*(0.9w/8) = 1.1125w, 与绘制逻辑保持一致. */
    private static final float H_OVER_W = 0.1f + (XiangqiGame.ROWS - 1) * 0.9f / (XiangqiGame.COLS - 1);

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int hAvail = MeasureSpec.getMode(hSpec) == MeasureSpec.UNSPECIFIED
                ? Integer.MAX_VALUE : MeasureSpec.getSize(hSpec);
        int h = Math.round(w * H_OVER_W);
        // 竖向空间不足时按高度反推宽度, 保证整盘完整可见、底部按钮不被挤出屏幕.
        // 父容器给的是固定宽高(EXACT)时也允许缩小, 由 FrameLayout 居中摆放.
        if (h > hAvail && hAvail > 0) {
            h = hAvail;
            w = Math.max(1, Math.round(h / H_OVER_W));
        }
        margin = w * 0.05f;
        cell = (w - 2 * margin) / (XiangqiGame.COLS - 1);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth();
        margin = w * 0.05f;
        cell = (w - 2 * margin) / (XiangqiGame.COLS - 1);
        cv.drawColor(boardBg);

        // 装饰内框(复刻 iOS): 距格线 0.28*cell 外扩, 圆角 3pt, 1.4pt 线, 线色 55% 不透明
        float off = cell * 0.28f;
        float rad = dp(3f);
        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeWidth(dp(1.4f));
        framePaint.setColor(lineColor);
        framePaint.setAlpha(140);
        cv.drawRoundRect(margin - off, margin - off, w - margin + off,
                getHeight() - margin + off, rad, rad, framePaint);

        // 棋盘外描边(复刻 iOS): 1pt 线, 贴画布边缘, 线色 70%; 让 1px 木色保证左右贴屏不被破
        float sw = dp(1f);
        framePaint.setStrokeWidth(sw);
        framePaint.setAlpha(179);
        float fi = sw / 2f + 1f;
        cv.drawRect(fi, fi, w - fi, getHeight() - fi, framePaint);

        line.setColor(lineColor);
        mark.setColor(lineColor);
        mark.setStyle(Paint.Style.FILL);

        // 横线 10 条
        line.setStrokeWidth(1.6f);
        for (int y = 0; y < XiangqiGame.ROWS; y++)
            cv.drawLine(margin, py(y), w - margin, py(y), line);
        // 竖线 9 条: 河界处断开(左右边线不断)
        for (int x = 0; x < XiangqiGame.COLS; x++) {
            if (x == 0 || x == XiangqiGame.COLS - 1) {
                cv.drawLine(px(x), py(0), px(x), py(XiangqiGame.ROWS - 1), line);
            } else {
                cv.drawLine(px(x), py(0), px(x), py(4), line);
                cv.drawLine(px(x), py(5), px(x), py(XiangqiGame.ROWS - 1), line);
            }
        }
        // 外框加粗(必须 STROKE, 否则 FILL 会把整个棋盘涂成线色)
        line.setStrokeWidth(3.2f);
        line.setStyle(Paint.Style.STROKE);
        cv.drawRect(margin, margin, w - margin, py(XiangqiGame.ROWS - 1), line);
        line.setStyle(Paint.Style.FILL);
        line.setStrokeWidth(1.6f);

        // 九宫斜线
        cv.drawLine(px(3), py(0), px(5), py(2), line);
        cv.drawLine(px(5), py(0), px(3), py(2), line);
        cv.drawLine(px(3), py(7), px(5), py(9), line);
        cv.drawLine(px(5), py(7), px(3), py(9), line);

        // 兵/炮位标记
        int[][] marks = {{1, 2}, {7, 2}, {1, 7}, {7, 7},
                {0, 3}, {2, 3}, {4, 3}, {6, 3}, {8, 3},
                {0, 6}, {2, 6}, {4, 6}, {6, 6}, {8, 6}};
        for (int[] m : marks) drawPointMark(cv, m[0], m[1]);

        // 楚河汉界
        text.setColor(lineColor);
        text.setAlpha(150);
        text.setTextSize(cell * 0.62f);
        float midY = (py(4) + py(5)) / 2 + cell * 0.22f;
        cv.drawText("楚  河", px(2), midY, text);
        cv.drawText("汉  界", px(6), midY, text);
        text.setAlpha(255);

        // 棋子
        float r = cell * 0.425f;
        for (int y = 0; y < XiangqiGame.ROWS; y++) for (int x = 0; x < XiangqiGame.COLS; x++) {
            int p = board[y][x];
            if (p == XiangqiGame.EMPTY) continue;
            drawPiece(cv, px(x), py(y), r, p);
        }

        // 合法落点(画在棋子之上: 空点是圆点, 可吃子处是外圈)
        for (XiangqiGame.Move h : hints) {
            int v = board[h.ty][h.tx];
            if (v == XiangqiGame.EMPTY) {
                fill.setColor(accent);
                fill.setAlpha(110);
                fill.setStyle(Paint.Style.FILL);
                cv.drawCircle(px(h.tx), py(h.ty), cell * 0.14f, fill);
            } else {
                mark.setColor(accent);
                mark.setStyle(Paint.Style.STROKE);
                mark.setStrokeWidth(3f);
                cv.drawCircle(px(h.tx), py(h.ty), cell * 0.48f, mark);
                mark.setStyle(Paint.Style.FILL);
            }
        }

        // 最后一步: 起点小方标记 + 终点圈(画在棋子之上才看得见)
        if (lastTx >= 0) {
            mark.setColor(accent);
            mark.setAlpha(170);
            mark.setStyle(Paint.Style.FILL);
            float s = cell * 0.11f;
            cv.drawRect(px(lastFx) - s, py(lastFy) - s, px(lastFx) + s, py(lastFy) + s, mark);
            if (board[lastTy][lastTx] != XiangqiGame.EMPTY) {
                mark.setStyle(Paint.Style.STROKE);
                mark.setStrokeWidth(2.6f);
                cv.drawCircle(px(lastTx), py(lastTy), cell * 0.48f, mark);
                mark.setStyle(Paint.Style.FILL);
            } else {
                cv.drawRect(px(lastTx) - s, py(lastTy) - s, px(lastTx) + s, py(lastTy) + s, mark);
            }
            mark.setAlpha(255);
        }
    }

    private void drawPointMark(Canvas cv, int x, int y) {
        float arm = cell * 0.12f, gap = cell * 0.09f;
        // 边缘判断按屏幕列: 翻转后逻辑 x=0 显示在最右, 不能再用逻辑 x 判断
        int sx0 = seatFlipped ? XiangqiGame.COLS - 1 - x : x;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                if (sx0 == 0 && sx < 0) continue;
                if (sx0 == XiangqiGame.COLS - 1 && sx > 0) continue;
                float cx = px(x) + sx * gap, cy = py(y) + sy * gap;
                cv.drawLine(cx, cy, cx + sx * arm, cy, line);
                cv.drawLine(cx, cy, cx, cy + sy * arm, line);
            }
        }
    }

    private void drawPiece(Canvas cv, float cx, float cy, float r, int p) {
        boolean red = XiangqiGame.colorOf(p) == XiangqiGame.RED;
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(Color.parseColor("#F6EBD8"));
        cv.drawCircle(cx, cy, r, fill);
        // 细边
        fill.setStyle(Paint.Style.STROKE);
        fill.setStrokeWidth(2.2f);
        fill.setColor(red ? redColor : blackColor);
        cv.drawCircle(cx, cy, r, fill);
        fill.setStrokeWidth(1f);
        fill.setColor(red ? redColor : blackColor);
        fill.setAlpha(70);
        cv.drawCircle(cx, cy, r * 0.82f, fill);
        fill.setAlpha(255);
        fill.setStyle(Paint.Style.FILL);

        text.setColor(red ? redColor : blackColor);
        text.setTextSize(cell * 0.62f);
        float ty = cy - (text.ascent() + text.descent()) / 2;
        // 仅双人对局: 黑方棋子文字倒置 180°, 让对坐的黑方看字是正的
        if (!red && flipBlackText) {
            cv.save();
            cv.rotate(180f, cx, cy);
            cv.drawText(XiangqiGame.pieceName(p), cx, ty, text);
            cv.restore();
        } else {
            cv.drawText(XiangqiGame.pieceName(p), cx, ty, text);
        }

        if (selX >= 0 && selY >= 0 && Math.abs(px(selX) - cx) < 1 && Math.abs(py(selY) - cy) < 1) {
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(3.2f);
            fill.setColor(accent);
            cv.drawCircle(cx, cy, r + cell * 0.06f, fill);
            fill.setStyle(Paint.Style.FILL);
        }
    }

    private float px(int x) {
        return margin + (seatFlipped ? XiangqiGame.COLS - 1 - x : x) * cell;
    }

    private float py(int y) {
        return margin + (seatFlipped ? XiangqiGame.ROWS - 1 - y : y) * cell;
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (listener == null && dragListener == null) return true;
        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            int[] p = pointOf(e.getX(), e.getY());
            downX = p == null ? -1 : p[0];
            downY = p == null ? -1 : p[1];
            return true;
        }
        if (e.getAction() == MotionEvent.ACTION_UP) {
            int[] p = pointOf(e.getX(), e.getY());
            if (p == null) { downX = downY = -1; return true; }
            int x = p[0], y = p[1];
            if (dragListener != null && downX >= 0 && (x != downX || y != downY)) {
                dragListener.onDrag(downX, downY, x, y);
            } else if (listener != null) {
                listener.onPointTap(x, y);
            }
            downX = downY = -1;
        }
        return true;
    }

    private int[] pointOf(float ex, float ey) {
        int sx = Math.round((ex - margin) / cell);
        int sy = Math.round((ey - margin) / cell);
        if (sx < 0 || sx >= XiangqiGame.COLS || sy < 0 || sy >= XiangqiGame.ROWS) return null;
        int x = seatFlipped ? XiangqiGame.COLS - 1 - sx : sx;
        int y = seatFlipped ? XiangqiGame.ROWS - 1 - sy : sy;
        float dx = ex - px(x), dy = ey - py(y);
        if (Math.hypot(dx, dy) > cell * 0.65f) return null;
        return new int[]{x, y};
    }
}
