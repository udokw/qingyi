package com.sijia.go;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 中国象棋棋局与规则.
 * 棋盘 9 列 × 10 行; (0,0) 为左上角, 红方在下(y=7..9), 黑方在上(y=0..2).
 * 规则覆盖: 马腿/象眼/炮架/过河兵/九宫/飞将, 将军/将死/困毙, 长将判负, 60 回合自然限着.
 */
public class XiangqiGame {
    public static final int EMPTY = 0, RED = 1, BLACK = 2;
    public static final int KING = 0, ADVISOR = 1, ELEPHANT = 2, HORSE = 3, ROOK = 4, CANNON = 5, PAWN = 6;
    public static final int COLS = 9, ROWS = 10;
    public static final int RESULT_NONE = 0, RESULT_DRAW = 3;

    public static class Move {
        public int fx, fy, tx, ty;
        public int piece, captured, color;
        public int prevNoCapture;

        public Move(int fx, int fy, int tx, int ty) {
            this.fx = fx; this.fy = fy; this.tx = tx; this.ty = ty;
        }

        public String uci() {
            return "" + (char) ('a' + fx) + (9 - fy) + (char) ('a' + tx) + (9 - ty);
        }
    }

    private final int[][] board = new int[ROWS][COLS];
    private int turn = RED;
    private final Deque<Move> history = new ArrayDeque<>();
    private final List<String> positionKeys = new ArrayList<>();
    private final List<Boolean> gaveCheck = new ArrayList<>();
    private int noCapturePlies = 0;
    private int result = RESULT_NONE;
    private String endReason = "";

    public XiangqiGame() { reset(); }

    // ---------------- 初始化 / 局面 ----------------
    public void reset() {
        for (int y = 0; y < ROWS; y++) for (int x = 0; x < COLS; x++) board[y][x] = EMPTY;
        int[] back = {ROOK, HORSE, ELEPHANT, ADVISOR, KING, ADVISOR, ELEPHANT, HORSE, ROOK};
        for (int x = 0; x < COLS; x++) {
            board[0][x] = piece(BLACK, back[x]);
            board[9][x] = piece(RED, back[x]);
        }
        board[2][1] = piece(BLACK, CANNON); board[2][7] = piece(BLACK, CANNON);
        board[7][1] = piece(RED, CANNON); board[7][7] = piece(RED, CANNON);
        for (int x = 0; x < COLS; x += 2) {
            board[3][x] = piece(BLACK, PAWN);
            board[6][x] = piece(RED, PAWN);
        }
        history.clear();
        positionKeys.clear();
        gaveCheck.clear();
        noCapturePlies = 0;
        turn = RED;
        result = RESULT_NONE;
        endReason = "";
        positionKeys.add(positionKey());
    }

    /** 从 FEN 载入(用于棋谱/残局) */
    public boolean loadFen(String fen) {
        try {
            String[] parts = fen.trim().split("\\s+");
            String[] ranks = parts[0].split("/");
            if (ranks.length != ROWS) return false;
            for (int y = 0; y < ROWS; y++) for (int x = 0; x < COLS; x++) board[y][x] = EMPTY;
            for (int y = 0; y < ROWS; y++) {
                int x = 0;
                for (char c : ranks[y].toCharArray()) {
                    if (Character.isDigit(c)) x += c - '0';
                    else {
                        int p = parseFenPiece(c);
                        if (p == 0 || x >= COLS) return false;
                        board[y][x++] = p;
                    }
                    if (x > COLS) return false;
                }
            }
            turn = parts.length > 1 && parts[1].equalsIgnoreCase("b") ? BLACK : RED;
            history.clear();
            positionKeys.clear();
            gaveCheck.clear();
            noCapturePlies = 0;
            result = RESULT_NONE;
            endReason = "";
            positionKeys.add(positionKey());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String fen() {
        StringBuilder sb = new StringBuilder();
        for (int y = 0; y < ROWS; y++) {
            int empty = 0;
            for (int x = 0; x < COLS; x++) {
                int p = board[y][x];
                if (p == EMPTY) { empty++; continue; }
                if (empty > 0) { sb.append(empty); empty = 0; }
                sb.append(fenChar(p));
            }
            if (empty > 0) sb.append(empty);
            if (y < ROWS - 1) sb.append('/');
        }
        sb.append(' ').append(turn == RED ? 'w' : 'b').append(" - - ")
                .append(noCapturePlies).append(' ').append(history.size() / 2 + 1);
        return sb.toString();
    }

    public String positionKey() {
        StringBuilder sb = new StringBuilder(100);
        for (int y = 0; y < ROWS; y++) for (int x = 0; x < COLS; x++) sb.append((char) ('a' + board[y][x]));
        sb.append(turn);
        return sb.toString();
    }

    public int pieceAt(int x, int y) {
        return in(x, y) ? board[y][x] : EMPTY;
    }

    public int[][] board() { return board; }

    public int turn() { return turn; }
    public int result() { return result; }
    public String endReason() { return endReason; }
    public boolean isOver() { return result != RESULT_NONE; }
    public int plies() { return history.size(); }
    public List<Move> history() { return new ArrayList<>(history); }
    public int kingX(int color) {
        for (int y = 0; y < ROWS; y++) for (int x = 0; x < COLS; x++)
            if (board[y][x] == piece(color, KING)) return x;
        return -1;
    }
    public int kingY(int color) {
        for (int y = 0; y < ROWS; y++) for (int x = 0; x < COLS; x++)
            if (board[y][x] == piece(color, KING)) return y;
        return -1;
    }

    // ---------------- 着法生成 ----------------
    public List<Move> legalMoves() { return legalMoves(turn); }

    public List<Move> legalMoves(int color) {
        List<Move> out = new ArrayList<>();
        for (int y = 0; y < ROWS; y++) for (int x = 0; x < COLS; x++) {
            if (board[y][x] == EMPTY || colorOf(board[y][x]) != color) continue;
            for (Move m : pseudoMoves(x, y)) {
                apply(m);
                if (!inCheck(color)) out.add(m);
                unapply(m);
            }
        }
        return out;
    }

    public List<Move> legalMovesFrom(int fx, int fy) {
        List<Move> out = new ArrayList<>();
        if (!in(fx, fy) || board[fy][fx] == EMPTY || colorOf(board[fy][fx]) != turn) return out;
        for (Move m : pseudoMoves(fx, fy)) {
            apply(m);
            if (!inCheck(turn)) out.add(m);
            unapply(m);
        }
        return out;
    }

    private List<Move> pseudoMoves(int x, int y) {
        List<Move> out = new ArrayList<>();
        int p = board[y][x];
        int c = colorOf(p), t = typeOf(p);
        switch (t) {
            case KING:
                for (int[] d : ORTHO) {
                    int nx = x + d[0], ny = y + d[1];
                    if (inPalace(nx, ny, c) && (board[ny][nx] == EMPTY || colorOf(board[ny][nx]) != c))
                        out.add(new Move(x, y, nx, ny));
                }
                break;
            case ADVISOR:
                for (int[] d : DIAG) {
                    int nx = x + d[0], ny = y + d[1];
                    if (inPalace(nx, ny, c) && (board[ny][nx] == EMPTY || colorOf(board[ny][nx]) != c))
                        out.add(new Move(x, y, nx, ny));
                }
                break;
            case ELEPHANT:
                for (int[] d : DIAG) {
                    int nx = x + d[0] * 2, ny = y + d[1] * 2;
                    int ex = x + d[0], ey = y + d[1];
                    if (!in(nx, ny) || crossesRiver(nx, ny, c)) continue;
                    if (board[ey][ex] == EMPTY && (board[ny][nx] == EMPTY || colorOf(board[ny][nx]) != c))
                        out.add(new Move(x, y, nx, ny));
                }
                break;
            case HORSE:
                for (int[] d : HORSE_MOVES) {
                    int nx = x + d[0], ny = y + d[1];
                    int lx = x + d[2], ly = y + d[3];
                    if (!in(nx, ny) || board[ly][lx] != EMPTY) continue;
                    if (board[ny][nx] == EMPTY || colorOf(board[ny][nx]) != c)
                        out.add(new Move(x, y, nx, ny));
                }
                break;
            case ROOK:
                for (int[] d : ORTHO) {
                    int nx = x + d[0], ny = y + d[1];
                    while (in(nx, ny) && board[ny][nx] == EMPTY) {
                        out.add(new Move(x, y, nx, ny));
                        nx += d[0]; ny += d[1];
                    }
                    if (in(nx, ny) && colorOf(board[ny][nx]) != c) out.add(new Move(x, y, nx, ny));
                }
                break;
            case CANNON:
                for (int[] d : ORTHO) {
                    int nx = x + d[0], ny = y + d[1];
                    while (in(nx, ny) && board[ny][nx] == EMPTY) {
                        out.add(new Move(x, y, nx, ny));
                        nx += d[0]; ny += d[1];
                    }
                    // 越过炮架后第一个子可吃
                    if (in(nx, ny)) {
                        nx += d[0]; ny += d[1];
                        while (in(nx, ny) && board[ny][nx] == EMPTY) { nx += d[0]; ny += d[1]; }
                        if (in(nx, ny) && colorOf(board[ny][nx]) != c) out.add(new Move(x, y, nx, ny));
                    }
                }
                break;
            case PAWN:
                int forward = c == RED ? -1 : 1;
                int fy = y + forward;
                if (in(x, fy) && (board[fy][x] == EMPTY || colorOf(board[fy][x]) != c))
                    out.add(new Move(x, y, x, fy));
                if (crossedRiverAt(x, y, c)) {
                    for (int dx = -1; dx <= 1; dx += 2) {
                        int nx = x + dx;
                        if (in(nx, y) && (board[y][nx] == EMPTY || colorOf(board[y][nx]) != c))
                            out.add(new Move(x, y, nx, y));
                    }
                }
                break;
        }
        return out;
    }

    // ---------------- 将军 / 飞将 ----------------
    public boolean inCheck(int color) {
        int kx = kingX(color), ky = kingY(color);
        if (kx < 0) return false;
        if (kingsFacing()) return true;
        int enemy = 3 - color;
        for (int y = 0; y < ROWS; y++) for (int x = 0; x < COLS; x++) {
            if (board[y][x] == EMPTY || colorOf(board[y][x]) != enemy) continue;
            for (Move m : pseudoMoves(x, y))
                if (m.tx == kx && m.ty == ky) return true;
        }
        return false;
    }

    /** 将帅同线且中间无子 */
    public boolean kingsFacing() {
        int rx = kingX(RED), ry = kingY(RED), bx = kingX(BLACK), by = kingY(BLACK);
        if (rx < 0 || bx < 0 || rx != bx) return false;
        int lo = Math.min(ry, by), hi = Math.max(ry, by);
        for (int y = lo + 1; y < hi; y++) if (board[y][rx] != EMPTY) return false;
        return true;
    }

    // ---------------- 落子 / 悔棋 ----------------
    public boolean play(int fx, int fy, int tx, int ty) {
        if (result != RESULT_NONE) return false;
        for (Move m : legalMovesFrom(fx, fy)) {
            if (m.tx == tx && m.ty == ty) {
                commit(m);
                return true;
            }
        }
        return false;
    }

    public boolean playUci(String uci) {
        int[][] sq = parseUciSquares(uci);
        if (sq == null) return false;
        int fx = sq[0][0], fr = sq[0][1], tx = sq[1][0], tr = sq[1][1];
        // rank 10 只可能来自 1-based 坐标(Fairy 大棋盘 UCI)
        if (fr == 10 || tr == 10) {
            if (fr < 1 || tr < 1) return false;
            return play(fx, 10 - fr, tx, 10 - tr);
        }
        // 标准 ICCS / Pikafish: rank 0 = 红方底线
        if (fr >= 0 && fr <= 9 && tr >= 0 && tr <= 9 && play(fx, 9 - fr, tx, 9 - tr))
            return true;
        // 兼容 Fairy 的 1-based 坐标
        if (fr >= 1 && tr >= 1) return play(fx, 10 - fr, tx, 10 - tr);
        return false;
    }

    /** Fairy 的 1-based UCI(a1..i10) 转为标准 0-based(a0..i9) */
    public static String normalizeFairyUci(String uci) {
        int[][] sq = parseUciSquares(uci);
        if (sq == null) return uci;
        int fr = sq[0][1], tr = sq[1][1];
        if (fr < 1 || fr > 10 || tr < 1 || tr > 10) return uci;
        return "" + (char) ('a' + sq[0][0]) + (fr - 1)
                + (char) ('a' + sq[1][0]) + (tr - 1);
    }

    private static int[][] parseUciSquares(String uci) {
        if (uci == null || uci.length() < 4) return null;
        int i = 0, n = uci.length();
        char f1 = Character.toLowerCase(uci.charAt(i++));
        if (f1 < 'a' || f1 > 'i') return null;
        int r1 = 0;
        boolean got = false;
        while (i < n && Character.isDigit(uci.charAt(i))) {
            r1 = r1 * 10 + (uci.charAt(i) - '0');
            i++; got = true;
        }
        if (!got || i >= n) return null;
        char f2 = Character.toLowerCase(uci.charAt(i++));
        if (f2 < 'a' || f2 > 'i') return null;
        int r2 = 0;
        got = false;
        while (i < n && Character.isDigit(uci.charAt(i))) {
            r2 = r2 * 10 + (uci.charAt(i) - '0');
            i++; got = true;
        }
        if (!got) return null;
        return new int[][]{{f1 - 'a', r1}, {f2 - 'a', r2}};
    }

    /** 给 UI 用的失手原因: 区分「棋子走法不对」与「会送将」 */
    public String illegalReason(int fx, int fy, int tx, int ty) {
        if (!in(fx, fy)) return "起点无效";
        int p = board[fy][fx];
        if (p == EMPTY) return "这里没有棋子";
        if (colorOf(p) != turn) return "还没轮到这一方";
        boolean reachable = false;
        for (Move m : pseudoMoves(fx, fy)) {
            if (m.tx == tx && m.ty == ty) { reachable = true; break; }
        }
        if (!reachable) return "这枚棋子不能这样走";
        Move probe = new Move(fx, fy, tx, ty);
        apply(probe);
        boolean selfCheck = inCheck(turn);
        unapply(probe);
        if (selfCheck) return "这一手会让自己的老将被将军";
        return "此着不合规则";
    }

    private void commit(Move m) {
        apply(m);
        m.color = turn;
        m.prevNoCapture = noCapturePlies;
        history.push(m);
        noCapturePlies = m.captured == EMPTY ? noCapturePlies + 1 : 0;
        turn = 3 - turn;
        gaveCheck.add(inCheck(turn));
        if (history.size() == 1) positionKeys.clear();
        positionKeys.add(positionKey());
        evaluateEnd();
    }

    private void evaluateEnd() {
        if (inCheck(turn) && legalMoves(turn).isEmpty()) {
            result = 3 - turn;
            endReason = (turn == RED ? "红方" : "黑方") + "被将死";
            return;
        }
        if (!inCheck(turn) && legalMoves(turn).isEmpty()) {
            result = 3 - turn;
            endReason = (turn == RED ? "红方" : "黑方") + "无子可走(困毙)";
            return;
        }
        if (noCapturePlies >= 120) {
            result = RESULT_DRAW;
            endReason = "六十回合无吃子, 判和";
            return;
        }
        int repeats = 0;
        String key = positionKeys.get(positionKeys.size() - 1);
        for (String k : positionKeys) if (k.equals(key)) repeats++;
        if (repeats >= 3) {
            int checkingSide = repeatedCheckingSide();
            if (checkingSide != 0) {
                result = 3 - checkingSide;
                endReason = (checkingSide == RED ? "红方" : "黑方") + "长将, 判负";
            } else {
                result = RESULT_DRAW;
                endReason = "三次重复局面, 判和";
            }
        }
    }

    /** 最近连续 6 个半回合若一方每着都将军, 返回该方(长将), 否则 0 */
    private int repeatedCheckingSide() {
        if (history.size() < 6) return 0;
        List<Move> hs = new ArrayList<>(history);   // 栈顶=最近
        int redCheck = 0, blackCheck = 0;
        for (int i = 0; i < 6; i++) {
            Move m = hs.get(i);
            boolean chk = gaveCheck.get(gaveCheck.size() - 1 - i);
            if (chk) { if (m.color == RED) redCheck++; else blackCheck++; }
        }
        if (redCheck == 3 && blackCheck == 0) return RED;
        if (blackCheck == 3 && redCheck == 0) return BLACK;
        return 0;
    }

    private void apply(Move m) {
        int p = board[m.fy][m.fx];
        m.piece = p;
        m.captured = board[m.ty][m.tx];
        board[m.ty][m.tx] = p;
        board[m.fy][m.fx] = EMPTY;
    }

    private void unapply(Move m) {
        board[m.fy][m.fx] = m.piece;
        board[m.ty][m.tx] = m.captured;
    }

    public Move undo() {
        Move m = history.poll();
        if (m == null) return null;
        unapply(m);
        noCapturePlies = m.prevNoCapture;
        turn = m.color;
        if (!gaveCheck.isEmpty()) gaveCheck.remove(gaveCheck.size() - 1);
        if (!positionKeys.isEmpty()) positionKeys.remove(positionKeys.size() - 1);
        result = RESULT_NONE;
        endReason = "";
        return m;
    }

    /** 认输 */
    public void resign(int color) {
        result = 3 - color;
        endReason = (color == RED ? "红方" : "黑方") + "认输";
    }

    /** 协议和棋 */
    public void agreeDraw() {
        result = RESULT_DRAW;
        endReason = "双方协议和棋";
    }

    // ---------------- 记谱 ----------------
    /** 中文纵线记谱, 如 炮二平五 / 马8进7 */
    public String notation(Move m) {
        int p = m.piece != 0 ? m.piece : board[m.fy][m.fx];
        int c = colorOf(p), t = typeOf(p);
        String name = pieceName(p);
        String num = fileNumber(m.fx, c);
        // 同线同兵种: 前/后/中 (排除已落子的目标格, 否则同线直走会被误判为双炮/双车)
        List<Integer> same = new ArrayList<>();
        for (int y = 0; y < ROWS; y++) if (board[y][m.fx] == p && y != m.ty) same.add(y);
        if (board[m.fy][m.fx] != p && m.fy != m.ty) same.add(m.fy);
        String prefix = "";
        String origin = num;
        if (same.size() > 1 && (t == ROOK || t == CANNON || t == PAWN || t == HORSE || t == ADVISOR || t == ELEPHANT)) {
            same.sort((a, b) -> c == RED ? a - b : b - a);   // 前 -> 后
            int idx = same.indexOf(m.fy);
            if (same.size() == 2) prefix = idx == 0 ? "前" : "后";
            else if (same.size() == 3) prefix = idx == 0 ? "前" : idx == 1 ? "中" : "后";
            else prefix = idx == 0 ? "前" : "后";
            origin = "";
        }
        String action;
        String dest;
        if (m.ty == m.fy) {
            action = "平";
            dest = fileNumber(m.tx, c);
        } else {
            boolean forward = c == RED ? m.ty < m.fy : m.ty > m.fy;
            action = forward ? "进" : "退";
            if (t == HORSE || t == ELEPHANT || t == ADVISOR) dest = fileNumber(m.tx, c);
            else dest = number(Math.abs(m.ty - m.fy), c);
        }
        return prefix + name + origin + action + dest;
    }

    public static String pieceName(int p) {
        switch (typeOf(p)) {
            case KING: return colorOf(p) == RED ? "帅" : "将";
            case ADVISOR: return colorOf(p) == RED ? "仕" : "士";
            case ELEPHANT: return colorOf(p) == RED ? "相" : "象";
            case HORSE: return "马";
            case ROOK: return "車";
            case CANNON: return "炮";
            case PAWN: return colorOf(p) == RED ? "兵" : "卒";
        }
        return "";
    }

    public String fileNumber(int x, int color) {
        // 红方从右往左 1-9(右下角为 1), 黑方从黑方右侧(屏幕左侧)往右 1-9
        return number(color == RED ? 9 - x : x + 1, color);
    }

    private static String number(int n, int color) {
        if (color == BLACK) return String.valueOf(n);
        String[] cn = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
        return n >= 0 && n <= 9 ? cn[n] : String.valueOf(n);
    }

    // ---------------- 编码 ----------------
    public static int piece(int color, int type) {
        return color == RED ? type + 1 : type + 8;
    }

    public static int colorOf(int p) { return p == EMPTY ? 0 : (p <= 7 ? RED : BLACK); }

    public static int typeOf(int p) { return p == EMPTY ? -1 : (p <= 7 ? p - 1 : p - 8); }

    private static char fenChar(int p) {
        char c;
        switch (typeOf(p)) {
            case KING: c = 'k'; break;
            case ADVISOR: c = 'a'; break;
            case ELEPHANT: c = 'b'; break;
            case HORSE: c = 'n'; break;
            case ROOK: c = 'r'; break;
            case CANNON: c = 'c'; break;
            default: c = 'p'; break;
        }
        return colorOf(p) == RED ? Character.toUpperCase(c) : c;
    }

    private static int parseFenPiece(char c) {
        int color = Character.isUpperCase(c) ? RED : BLACK;
        switch (Character.toLowerCase(c)) {
            case 'k': return piece(color, KING);
            case 'a': return piece(color, ADVISOR);
            case 'b': return piece(color, ELEPHANT);
            case 'n': return piece(color, HORSE);
            case 'r': return piece(color, ROOK);
            case 'c': return piece(color, CANNON);
            case 'p': return piece(color, PAWN);
        }
        return 0;
    }

    // ---------------- 内部 ----------------
    private static final int[][] ORTHO = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIAG = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    // {dx, dy, 马腿x, 马腿y}
    private static final int[][] HORSE_MOVES = {
            {1, -2, 0, -1}, {-1, -2, 0, -1}, {1, 2, 0, 1}, {-1, 2, 0, 1},
            {2, -1, 1, 0}, {2, 1, 1, 0}, {-2, -1, -1, 0}, {-2, 1, -1, 0}
    };

    private boolean in(int x, int y) { return x >= 0 && x < COLS && y >= 0 && y < ROWS; }

    private boolean inPalace(int x, int y, int color) {
        if (x < 3 || x > 5) return false;
        return color == RED ? y >= 7 && y <= 9 : y >= 0 && y <= 2;
    }

    private boolean crossesRiver(int x, int y, int color) {
        return color == RED ? y <= 4 : y >= 5;
    }

    private boolean crossedRiverAt(int x, int y, int color) {
        return color == RED ? y <= 4 : y >= 5;
    }
}
