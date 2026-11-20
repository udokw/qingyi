package com.sijia.go.banqi;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/**
 * 暗棋（翻翻棋）规则引擎。
 *
 * <p>纯 Java 实现，不依赖 Android，便于单独编译与单元自测。棋盘为 4 行 × 8 列，
 * 坐标 (x, y)：x 从左到右 0..7，y 从上到下 0..3。</p>
 *
 * <p>规则要点：</p>
 * <ul>
 *   <li>每回合二选一：翻任意暗子，或走一枚己方明子一格/吃子；</li>
 *   <li>首翻定色，之后翻出的子按棋子颜色归属；</li>
 *   <li>军衔 將 &gt; 士 &gt; 象 &gt; 車 &gt; 馬 &gt; 炮 &gt; 卒，同级可互吃；</li>
 *   <li>卒可吃將，將不能吃卒；</li>
 *   <li>炮只能走一格空格，吃子必须隔恰好一枚炮架跳吃，炮架可为明子或暗子；</li>
 *   <li>无合法步判负；连续 30 步无吃子无翻子判和；同一局面三次判和。</li>
 * </ul>
 */
public class BanqiGame {

    public static final int EMPTY = 0;
    /** 联机镜像时“有子但身份未知”的占位编码。 */
    public static final int HIDDEN = -1;

    public static final int RED = 1;
    public static final int BLACK = 2;

    /** 棋子类型。 */
    public static final int KING = 0;
    public static final int ADVISOR = 1;
    public static final int ELEPHANT = 2;
    public static final int ROOK = 3;
    public static final int HORSE = 4;
    public static final int CANNON = 5;
    public static final int PAWN = 6;

    public static final int ROWS = 4;
    public static final int COLS = 8;

    public static final int RESULT_NONE = 0;
    public static final int RESULT_DRAW = 3;

    /** 终局原因，供联机协议 reason 字段映射。 */
    public static final int END_RULE = 0;
    public static final int END_RESIGN = 1;
    public static final int END_TIMEOUT = 2;
    public static final int END_DISCONNECT = 3;
    public static final int END_NO_MOVES = 4;
    public static final int END_DRAW = 5;

    public static final int SEAT_FIRST = 1;
    public static final int SEAT_SECOND = 2;

    /** 双方合计连续多少步无吃子无翻子判和。 */
    public static final int DRAW_IDLE_PLIES = 30;
    /** 同一局面出现多少次判和。 */
    public static final int REPEAT_DRAW_COUNT = 3;

    private static final int[] COUNTS = {1, 2, 2, 2, 2, 2, 5};
    private static final int[] RANKS = {7, 6, 5, 4, 3, 2, 1};
    private static final String[] NAMES = {"將", "士", "象", "車", "馬", "炮", "卒"};
    /** 与现有象棋 FEN 风格一致：k/a/b/r/n/c/p，红大写、黑小写。 */
    private static final char[] FEN_CHARS = {'k', 'a', 'b', 'r', 'n', 'c', 'p'};
    private static final int[][] ORTHO = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private final int[][] board = new int[ROWS][COLS];
    private final boolean[][] faceUp = new boolean[ROWS][COLS];
    /** 下标 1/2 为座位号，值为 RED/BLACK，0 表示首翻前未定。 */
    private final int[] seatColor = new int[3];

    private int turn = SEAT_FIRST;
    private int idlePlies;
    private int plies;
    private int result = RESULT_NONE;
    private String endReason = "";
    private int endCause = END_RULE;
    /** true 表示暗子携带真实身份（单机）；false 表示联机镜像，暗子为 HIDDEN。 */
    private boolean fullSetKnown = true;

    private final Deque<Move> history = new ArrayDeque<Move>();
    private final List<String> positionKeys = new ArrayList<String>();

    /** 一步棋。翻子时 tx/ty 为 -1。 */
    public static class Move {
        public final boolean flip;
        public final int x;
        public final int y;
        public final int tx;
        public final int ty;

        /** 执行后：翻出的子 / 移动的子。 */
        public int piece;
        /** 执行后：被吃的子，0 表示无。 */
        public int captured;
        /** 执行时轮到哪个座位。 */
        public int seat;

        int prevTurn;
        int prevSeat1;
        int prevSeat2;
        int prevIdle;
        int prevBoard;

        public Move(int x, int y) {
            this.flip = true;
            this.x = x;
            this.y = y;
            this.tx = -1;
            this.ty = -1;
        }

        public Move(int fx, int fy, int tx, int ty) {
            this.flip = false;
            this.x = fx;
            this.y = fy;
            this.tx = tx;
            this.ty = ty;
        }

        /** 协议/日志用的稳定键。 */
        public String key() {
            return flip ? "f" + x + "," + y : "m" + x + "," + y + "-" + tx + "," + ty;
        }

        @Override
        public String toString() {
            return key();
        }
    }

    public BanqiGame() {
        reset();
    }

    public BanqiGame(long seed) {
        reset(seed);
    }

    private BanqiGame(BanqiGame src) {
        for (int y = 0; y < ROWS; y++) {
            for (int x = 0; x < COLS; x++) {
                board[y][x] = src.board[y][x];
                faceUp[y][x] = src.faceUp[y][x];
            }
        }
        seatColor[1] = src.seatColor[1];
        seatColor[2] = src.seatColor[2];
        turn = src.turn;
        idlePlies = src.idlePlies;
        plies = src.plies;
        result = src.result;
        endReason = src.endReason;
        endCause = src.endCause;
        fullSetKnown = src.fullSetKnown;
        positionKeys.addAll(src.positionKeys);
    }

    /** 供 AI 推演使用：复制完整局面，历史不复制。 */
    public BanqiGame copyForSearch() {
        return new BanqiGame(this);
    }

    // ------------------------------------------------------------------
    // 初始化
    // ------------------------------------------------------------------

    public void reset() {
        reset(new Random().nextLong());
    }

    /** 洗牌并铺满 32 格，全部背面朝上，座位颜色未定，先手先翻。 */
    public void reset(long seed) {
        Random random = new Random(seed);
        List<Integer> bag = new ArrayList<Integer>(32);
        for (int color = RED; color <= BLACK; color++) {
            for (int type = 0; type < COUNTS.length; type++) {
                for (int i = 0; i < COUNTS[type]; i++) {
                    bag.add(piece(color, type));
                }
            }
        }
        for (int i = bag.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            Integer tmp = bag.get(i);
            bag.set(i, bag.get(j));
            bag.set(j, tmp);
        }
        int index = 0;
        for (int y = 0; y < ROWS; y++) {
            for (int x = 0; x < COLS; x++) {
                board[y][x] = bag.get(index++);
                faceUp[y][x] = false;
            }
        }
        seatColor[1] = 0;
        seatColor[2] = 0;
        turn = BanqiOptions.firstSeat == SEAT_SECOND ? SEAT_SECOND : SEAT_FIRST;
        idlePlies = 0;
        plies = 0;
        result = RESULT_NONE;
        endReason = "";
        endCause = END_RULE;
        fullSetKnown = true;
        history.clear();
        positionKeys.clear();
        positionKeys.add(positionKey());
    }

    /**
     * 测试/残局摆盘。encoded 中 0=空格，HIDDEN=身份未知的暗子，其余为真实编码。
     * 会自动按 seat1Color 推导 seat2Color。
     */
    public void setup(int[][] encoded, boolean[][] up, int seat1Color, int turnSeat, int idle) {
        setup(encoded, up, seat1Color, turnSeat, idle, true);
    }

    public void setup(int[][] encoded, boolean[][] up, int seat1Color, int turnSeat,
                      int idle, boolean known) {
        for (int y = 0; y < ROWS; y++) {
            for (int x = 0; x < COLS; x++) {
                int p = encoded == null ? EMPTY : encoded[y][x];
                board[y][x] = p;
                faceUp[y][x] = up != null && up[y][x] && p != EMPTY;
            }
        }
        fullSetKnown = known;
        seatColor[1] = (seat1Color == RED || seat1Color == BLACK) ? seat1Color : 0;
        seatColor[2] = seatColor[1] == 0 ? 0 : 3 - seatColor[1];
        turn = turnSeat == SEAT_SECOND ? SEAT_SECOND : SEAT_FIRST;
        idlePlies = Math.max(0, idle);
        plies = 0;
        result = RESULT_NONE;
        endReason = "";
        endCause = END_RULE;
        history.clear();
        positionKeys.clear();
        positionKeys.add(positionKey());
    }

    // ------------------------------------------------------------------
    // 编码与查询
    // ------------------------------------------------------------------

    public static int piece(int color, int type) {
        return color == RED ? type + 1 : type + 9;
    }

    public static int colorOf(int p) {
        if (p <= 0) {
            return 0;
        }
        return p <= 7 ? RED : BLACK;
    }

    public static int typeOf(int p) {
        if (p <= 0) {
            return -1;
        }
        return p <= 7 ? p - 1 : p - 9;
    }

    public static int rankOf(int type) {
        return (type >= 0 && type < RANKS.length) ? RANKS[type] : 0;
    }

    public static String pieceName(int p) {
        int type = typeOf(p);
        return type >= 0 ? NAMES[type] : "?";
    }

    public static String colorName(int color) {
        if (color == RED) {
            return "红";
        }
        if (color == BLACK) {
            return "黑";
        }
        return "未定";
    }

    public static String coord(int x, int y) {
        return String.valueOf((char) ('a' + x)) + (y + 1);
    }

    public static boolean inBoard(int x, int y) {
        return x >= 0 && x < COLS && y >= 0 && y < ROWS;
    }

    /** 该格是否有棋子（暗子身份未知也算有子）。 */
    public boolean hasPieceAt(int x, int y) {
        return inBoard(x, y) && board[y][x] != EMPTY;
    }

    public boolean isFaceUp(int x, int y) {
        return inBoard(x, y) && faceUp[y][x];
    }

    public boolean isHiddenAt(int x, int y) {
        return inBoard(x, y) && board[y][x] != EMPTY && !faceUp[y][x];
    }

    public boolean isEmptyAt(int x, int y) {
        return inBoard(x, y) && board[y][x] == EMPTY;
    }

    /** 返回编码；暗子可能返回 HIDDEN（联机镜像）。UI 必须先判断 isFaceUp。 */
    public int pieceCodeAt(int x, int y) {
        return inBoard(x, y) ? board[y][x] : EMPTY;
    }

    public int turnSeat() {
        return turn;
    }

    /** 当前行动方的颜色；首翻前为 0。 */
    public int turnColor() {
        return seatColor[turn];
    }

    public int colorOfSeat(int seat) {
        return (seat == SEAT_FIRST || seat == SEAT_SECOND) ? seatColor[seat] : 0;
    }

    /** 颜色对应的座位；未定返回 0。 */
    public int seatOfColor(int color) {
        if (color == RED || color == BLACK) {
            for (int seat = SEAT_FIRST; seat <= SEAT_SECOND; seat++) {
                if (seatColor[seat] == color) {
                    return seat;
                }
            }
        }
        return 0;
    }

    public int idlePlies() {
        return idlePlies;
    }

    public int plies() {
        return plies;
    }

    public int result() {
        return result;
    }

    public String endReason() {
        return endReason;
    }

    public boolean isOver() {
        return result != RESULT_NONE;
    }

    public boolean isFullSetKnown() {
        return fullSetKnown;
    }

    public List<Move> historySnapshot() {
        return new ArrayList<Move>(history);
    }

    public int repeatCountAtCurrent() {
        String key = positionKey();
        int count = 0;
        for (String s : positionKeys) {
            if (s.equals(key)) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------
    // 规则判定
    // ------------------------------------------------------------------

    /** 走步吃子：同级可互吃；將不能吃卒、卒能吃將、炮不能走步吃任何子。 */
    public static boolean canCaptureByStep(int attacker, int defender) {
        if (attacker <= 0 || defender <= 0) {
            return false;
        }
        int a = typeOf(attacker);
        int d = typeOf(defender);
        if (a == CANNON && !BanqiOptions.cannonCanCaptureByStep) {
            return false;
        }
        if (a == KING && d == PAWN) {
            return false;
        }
        if (a == PAWN && d == KING) {
            return true;
        }
        return BanqiOptions.equalRankCanCapture
                ? rankOf(a) >= rankOf(d) : rankOf(a) > rankOf(d);
    }

    // ------------------------------------------------------------------
    // 合法着生成
    // ------------------------------------------------------------------

    public List<Move> legalActions() {
        List<Move> out = new ArrayList<Move>();
        if (result != RESULT_NONE) {
            return out;
        }
        addFlips(out);
        int color = seatColor[turn];
        if (color != 0) {
            addMovesForColor(out, color);
        }
        return out;
    }

    public List<Move> legalFlips() {
        List<Move> out = new ArrayList<Move>();
        if (result != RESULT_NONE) {
            return out;
        }
        addFlips(out);
        return out;
    }

    /** 某格明子的所有走子/吃子（不含翻子）。 */
    public List<Move> legalMovesFrom(int x, int y) {
        List<Move> out = new ArrayList<Move>();
        if (result != RESULT_NONE || !isFaceUp(x, y)) {
            return out;
        }
        int p = board[y][x];
        if (colorOf(p) != seatColor[turn]) {
            return out;
        }
        addMovesFrom(out, x, y, p);
        return out;
    }

    /** 指定颜色（不看轮次）的全部走子/吃子，供 AI 评估使用。 */
    public List<Move> movesForColor(int color) {
        List<Move> out = new ArrayList<Move>();
        if ((color == RED || color == BLACK) && result == RESULT_NONE) {
            addMovesForColor(out, color);
        }
        return out;
    }

    private void addFlips(List<Move> out) {
        for (int y = 0; y < ROWS; y++) {
            for (int x = 0; x < COLS; x++) {
                if (board[y][x] != EMPTY && !faceUp[y][x]) {
                    out.add(new Move(x, y));
                }
            }
        }
    }

    private void addMovesForColor(List<Move> out, int color) {
        for (int y = 0; y < ROWS; y++) {
            for (int x = 0; x < COLS; x++) {
                int p = board[y][x];
                if (faceUp[y][x] && p != EMPTY && colorOf(p) == color) {
                    addMovesFrom(out, x, y, p);
                }
            }
        }
    }

    private void addMovesFrom(List<Move> out, int x, int y, int p) {
        int color = colorOf(p);
        for (int[] d : ORTHO) {
            int nx = x + d[0];
            int ny = y + d[1];
            if (!inBoard(nx, ny)) {
                continue;
            }
            int q = board[ny][nx];
            if (q == EMPTY) {
                out.add(new Move(x, y, nx, ny));
            } else if (faceUp[ny][nx] && colorOf(q) != color && canCaptureByStep(p, q)) {
                Move m = new Move(x, y, nx, ny);
                m.piece = p;
                m.captured = q;
                out.add(m);
            }
        }
        if (typeOf(p) == CANNON) {
            addCannonJumps(out, x, y, p);
        }
    }

    /** 炮隔一子跳吃：炮架可为明子/暗子、任意颜色；目标必须是对方明子；不可连跳。 */
    private void addCannonJumps(List<Move> out, int x, int y, int p) {
        int color = colorOf(p);
        for (int[] d : ORTHO) {
            int sx = x + d[0];
            int sy = y + d[1];
            while (inBoard(sx, sy) && board[sy][sx] == EMPTY) {
                sx += d[0];
                sy += d[1];
            }
            if (!inBoard(sx, sy)) {
                continue;
            }
            if (!faceUp[sy][sx] && !BanqiOptions.cannonScreenMayBeHidden) {
                continue;
            }
            int tx = sx + d[0];
            int ty = sy + d[1];
            while (inBoard(tx, ty) && board[ty][tx] == EMPTY) {
                tx += d[0];
                ty += d[1];
            }
            if (!inBoard(tx, ty) || !faceUp[ty][tx]) {
                continue;
            }
            int q = board[ty][tx];
            if (colorOf(q) == color) {
                continue;
            }
            Move m = new Move(x, y, tx, ty);
            m.piece = p;
            m.captured = q;
            out.add(m);
        }
    }

    // ------------------------------------------------------------------
    // 执行与撤销
    // ------------------------------------------------------------------

    /** 按坐标走子；成功返回 true。 */
    public boolean play(int fx, int fy, int tx, int ty) {
        if (result != RESULT_NONE) {
            return false;
        }
        List<Move> legal = legalMovesFrom(fx, fy);
        for (Move m : legal) {
            if (m.tx == tx && m.ty == ty) {
                commitMove(m);
                return true;
            }
        }
        return false;
    }

    /** 单机/本地双人翻子：从引擎内部读取暗子身份。联机镜像请用 flipWith。 */
    public boolean flip(int x, int y) {
        if (result != RESULT_NONE || !inBoard(x, y)) {
            return false;
        }
        int p = board[y][x];
        if (p == EMPTY || p == HIDDEN || faceUp[y][x]) {
            return false;
        }
        return flipWith(x, y, p);
    }

    /** 翻子并显式指定翻出的真实身份（联机客机按房主裁决调用）。 */
    public boolean flipWith(int x, int y, int revealed) {
        if (result != RESULT_NONE || !inBoard(x, y) || faceUp[y][x]) {
            return false;
        }
        if (board[y][x] == EMPTY || revealed <= 0 || typeOf(revealed) < 0) {
            return false;
        }
        Move m = new Move(x, y);
        m.prevBoard = board[y][x];
        m.piece = revealed;
        m.seat = turn;
        m.prevTurn = turn;
        m.prevSeat1 = seatColor[1];
        m.prevSeat2 = seatColor[2];
        m.prevIdle = idlePlies;

        if (seatColor[1] == 0 && seatColor[2] == 0) {
            int color = colorOf(revealed);
            seatColor[turn] = color;
            seatColor[3 - turn] = 3 - color;
        }
        board[y][x] = revealed;
        faceUp[y][x] = true;
        finishAction(m, true);
        return true;
    }

    /** 执行一个由 legalActions() 产生的着法；翻子时按引擎内部身份翻开。 */
    public boolean play(Move m) {
        if (m == null) {
            return false;
        }
        if (m.flip) {
            if (m.piece > 0) {
                return flipWith(m.x, m.y, m.piece);
            }
            return flip(m.x, m.y);
        }
        return play(m.x, m.y, m.tx, m.ty);
    }

    private void commitMove(Move m) {
        m.seat = turn;
        m.prevTurn = turn;
        m.prevSeat1 = seatColor[1];
        m.prevSeat2 = seatColor[2];
        m.prevIdle = idlePlies;
        m.piece = board[m.y][m.x];
        m.captured = board[m.ty][m.tx];

        board[m.ty][m.tx] = m.piece;
        board[m.y][m.x] = EMPTY;
        faceUp[m.ty][m.tx] = true;
        faceUp[m.y][m.x] = false;

        finishAction(m, m.captured != EMPTY);
    }

    private void finishAction(Move m, boolean progress) {
        history.push(m);
        plies++;
        idlePlies = progress ? 0 : idlePlies + 1;
        turn = 3 - turn;
        positionKeys.add(positionKey());
        evaluateEnd();
    }

    /** 撤销最后一步；返回被撤销的着法，无历史时返回 null。 */
    public Move undo() {
        Move m = history.poll();
        if (m == null) {
            return null;
        }
        if (m.flip) {
            board[m.y][m.x] = m.prevBoard;
            faceUp[m.y][m.x] = false;
        } else {
            board[m.y][m.x] = m.piece;
            board[m.ty][m.tx] = m.captured;
            faceUp[m.y][m.x] = true;
            faceUp[m.ty][m.tx] = m.captured != EMPTY;
        }
        seatColor[1] = m.prevSeat1;
        seatColor[2] = m.prevSeat2;
        turn = m.prevTurn;
        idlePlies = m.prevIdle;
        plies = Math.max(0, plies - 1);
        result = RESULT_NONE;
        endReason = "";
        endCause = END_RULE;
        if (!positionKeys.isEmpty()) {
            positionKeys.remove(positionKeys.size() - 1);
        }
        return m;
    }

    // ------------------------------------------------------------------
    // 终局判定
    // ------------------------------------------------------------------

    /** 指定座位是否还有合法动作（可翻的暗子，或己方明子可走/可吃）。 */
    public boolean hasAnyAction(int seat) {
        for (int y = 0; y < ROWS; y++) {
            for (int x = 0; x < COLS; x++) {
                if (board[y][x] != EMPTY && !faceUp[y][x]) {
                    return true;
                }
            }
        }
        int color = colorOfSeat(seat);
        return color != 0 && !movesForColor(color).isEmpty();
    }

    /** 外部在摆盘/恢复快照后主动跑一次终局判定。 */
    public void refreshEnd() {
        evaluateEnd();
    }

    private void evaluateEnd() {
        if (result != RESULT_NONE) {
            return;
        }
        if (!hasAnyAction(turn)) {
            int loserColor = seatColor[turn];
            int winnerColor = seatColor[3 - turn];
            if (winnerColor == 0) {
                result = RESULT_DRAW;
                endCause = END_DRAW;
                endReason = "双方均无子可动，判和";
            } else {
                result = winnerColor;
                endCause = END_NO_MOVES;
                endReason = colorName(winnerColor) + "方获胜 · "
                        + (loserColor == 0 ? "对方" : colorName(loserColor) + "方") + "无子可动";
            }
            return;
        }
        String current = positionKeys.get(positionKeys.size() - 1);
        int repeat = 0;
        for (String key : positionKeys) {
            if (key.equals(current)) {
                repeat++;
            }
        }
        if (repeat >= REPEAT_DRAW_COUNT) {
            result = RESULT_DRAW;
            endCause = END_DRAW;
            endReason = "同一局面出现三次，判和";
            return;
        }
        int idleLimit = BanqiOptions.drawIdlePlies > 0
                ? BanqiOptions.drawIdlePlies : DRAW_IDLE_PLIES;
        if (idlePlies >= idleLimit) {
            result = RESULT_DRAW;
            endCause = END_DRAW;
            endReason = "连续 " + idleLimit + " 步无吃子无翻子，判和";
        }
    }

    /** 终局原因：END_RULE / END_RESIGN / END_TIMEOUT / END_DISCONNECT / END_NO_MOVES / END_DRAW。 */
    public int endCause() {
        return endCause;
    }

    public void resign(int seat) {
        endByCause(seat, END_RESIGN);
    }

    /**
     * 以指定原因终局（认输 / 超时 / 断线 / 无子可动）。
     * 颜色未定时按和棋处理，避免首翻前超时产生不存在的胜方。
     */
    public void endByCause(int loserSeat, int cause) {
        if (result != RESULT_NONE) {
            return;
        }
        int loser = loserSeat == SEAT_SECOND ? SEAT_SECOND : SEAT_FIRST;
        endCause = cause;
        int loserColor = colorOfSeat(loser);
        int winnerColor = colorOfSeat(3 - loser);
        if (winnerColor == 0) {
            result = RESULT_DRAW;
            endReason = causeWord(cause) + "（颜色未定），判和";
            return;
        }
        result = winnerColor;
        endReason = (loserColor == 0 ? "对方" : colorName(loserColor) + "方")
                + causeWord(cause) + "，" + colorName(winnerColor) + "方胜";
    }

    private static String causeWord(int cause) {
        if (cause == END_TIMEOUT) return "超时";
        if (cause == END_DISCONNECT) return "断线";
        if (cause == END_NO_MOVES) return "无子可动";
        return "认输";
    }

    public void agreeDraw() {
        if (result == RESULT_NONE) {
            result = RESULT_DRAW;
            endCause = END_DRAW;
            endReason = "双方同意和棋";
        }
    }

    public int winnerSeat() {
        int color = result;
        return (color == RED || color == BLACK) ? seatOfColor(color) : 0;
    }

    // ------------------------------------------------------------------
    // 暗子池与局面键
    // ------------------------------------------------------------------

    /** 剩余暗子的身份计数（下标为编码 1..15）；联机镜像返回 null。 */
    public int[] unseenPool() {
        if (!fullSetKnown) {
            return null;
        }
        int[] pool = new int[16];
        for (int y = 0; y < ROWS; y++) {
            for (int x = 0; x < COLS; x++) {
                int p = board[y][x];
                if (p > 0 && !faceUp[y][x]) {
                    pool[p]++;
                }
            }
        }
        for (int p = 1; p < pool.length; p++) {
            if (pool[p] > 0 && typeOf(p) < 0) {
                return null;
            }
        }
        return pool;
    }

    /** 局面键：只含明面、暗子位置、轮次、座位颜色；不含暗子身份。 */
    public String positionKey() {
        StringBuilder sb = new StringBuilder(48);
        for (int y = 0; y < ROWS; y++) {
            for (int x = 0; x < COLS; x++) {
                int p = board[y][x];
                if (p == EMPTY) {
                    sb.append('.');
                } else if (!faceUp[y][x]) {
                    sb.append('x');
                } else {
                    sb.append(fenChar(p));
                }
            }
            if (y < ROWS - 1) {
                sb.append('/');
            }
        }
        sb.append('|').append(turn);
        sb.append('|').append(seatColor[1]).append(seatColor[2]);
        return sb.toString();
    }

    /**
     * 联机快照/镜像 FEN：
     * <pre>xxxxxxxx/xxxxxxxx/xxxxxxxx/xxxxxxxx 1 -- 0 0 0</pre>
     * 依次为：棋盘、轮次座位、两座位颜色（r/b/-）、idle、ply、result。
     * 暗子一律写作 x，不泄漏身份。
     */
    public String fen() {
        StringBuilder sb = new StringBuilder(64);
        for (int y = 0; y < ROWS; y++) {
            for (int x = 0; x < COLS; x++) {
                int p = board[y][x];
                if (p == EMPTY) {
                    sb.append('.');
                } else if (!faceUp[y][x]) {
                    sb.append('x');
                } else {
                    sb.append(fenChar(p));
                }
            }
            if (y < ROWS - 1) {
                sb.append('/');
            }
        }
        sb.append(' ').append(turn).append(' ');
        sb.append(seatColor[1] == 0 ? '-' : (seatColor[1] == RED ? 'r' : 'b'));
        sb.append(seatColor[2] == 0 ? '-' : (seatColor[2] == RED ? 'r' : 'b'));
        sb.append(' ').append(idlePlies).append(' ').append(plies).append(' ').append(result);
        return sb.toString();
    }

    /** 从 fen() 恢复；暗子身份未知，fullSetKnown 置为 false。 */
    public boolean loadFen(String fen) {
        if (fen == null) {
            return false;
        }
        String[] parts = fen.trim().split("\\s+");
        if (parts.length < 6) {
            return false;
        }
        String[] rows = parts[0].split("/");
        if (rows.length != ROWS) {
            return false;
        }
        int[][] next = new int[ROWS][COLS];
        boolean[][] nextUp = new boolean[ROWS][COLS];
        for (int y = 0; y < ROWS; y++) {
            if (rows[y].length() != COLS) {
                return false;
            }
            for (int x = 0; x < COLS; x++) {
                char c = rows[y].charAt(x);
                if (c == '.') {
                    next[y][x] = EMPTY;
                } else if (c == 'x') {
                    next[y][x] = HIDDEN;
                } else {
                    int p = decodeFenChar(c);
                    if (p == EMPTY) {
                        return false;
                    }
                    next[y][x] = p;
                    nextUp[y][x] = true;
                }
            }
        }
        try {
            turn = Integer.parseInt(parts[1]) == SEAT_SECOND ? SEAT_SECOND : SEAT_FIRST;
            seatColor[1] = parseColorChar(parts[2].charAt(0));
            seatColor[2] = parseColorChar(parts[2].charAt(1));
            idlePlies = Math.max(0, Integer.parseInt(parts[3]));
            plies = Math.max(0, Integer.parseInt(parts[4]));
            result = Integer.parseInt(parts[5]);
        } catch (RuntimeException e) {
            return false;
        }
        for (int y = 0; y < ROWS; y++) {
            for (int x = 0; x < COLS; x++) {
                board[y][x] = next[y][x];
                faceUp[y][x] = nextUp[y][x];
            }
        }
        fullSetKnown = false;
        endReason = "";
        history.clear();
        positionKeys.clear();
        positionKeys.add(positionKey());
        return true;
    }

    // ------------------------------------------------------------------
    // 展示
    // ------------------------------------------------------------------

    public String notation(Move m) {
        if (m == null) {
            return "";
        }
        if (m.flip) {
            return "翻 " + coord(m.x, m.y) + " " + colorName(colorOf(m.piece)) + pieceName(m.piece);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(colorName(colorOf(m.piece))).append(pieceName(m.piece)).append(' ')
                .append(coord(m.x, m.y)).append('→').append(coord(m.tx, m.ty));
        if (m.captured != EMPTY) {
            sb.append(" 吃 ").append(colorName(colorOf(m.captured))).append(pieceName(m.captured));
        }
        return sb.toString();
    }

    private static char fenChar(int p) {
        char c = FEN_CHARS[typeOf(p)];
        return colorOf(p) == RED ? Character.toUpperCase(c) : c;
    }

    private static int decodeFenChar(char c) {
        char lower = Character.toLowerCase(c);
        for (int type = 0; type < FEN_CHARS.length; type++) {
            if (FEN_CHARS[type] == lower) {
                return piece(Character.isUpperCase(c) ? RED : BLACK, type);
            }
        }
        return EMPTY;
    }

    private static int parseColorChar(char c) {
        if (c == 'r' || c == 'R') {
            return RED;
        }
        if (c == 'b' || c == 'B') {
            return BLACK;
        }
        return 0;
    }
}
