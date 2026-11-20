package com.sijia.go.net;

import com.sijia.go.banqi.BanqiGame;

import org.json.JSONObject;

import java.util.List;

/**
 * 暗棋规则适配器：封装 BanqiGame，接入现有 LAN 协议。
 * 提示 / 发现 / 握手 / 重连 / 房主权威全部复用；暗子身份只由房主掌握。
 */
public class BanqiRules implements GameAdapter {
    private BanqiGame game = new BanqiGame();

    public BanqiGame game() {
        return game;
    }

    @Override
    public String id() {
        return BANQI;
    }

    @Override
    public String label() {
        return "暗棋";
    }

    @Override
    public int boardSize() {
        return BanqiGame.ROWS * BanqiGame.COLS;
    }

    @Override
    public void reset() {
        game = new BanqiGame();
    }

    @Override
    public int turn() {
        return game.turnSeat();
    }

    @Override
    public int moveCount() {
        return game.plies();
    }

    @Override
    public boolean isOver() {
        return game.isOver();
    }

    @Override
    public String hash(int moveNo) {
        return game.positionKey();
    }

    @Override
    public JSONObject snapshot() {
        JSONObject o = new JSONObject();
        try {
            o.put("fen", game.fen());
            o.put("moveNo", game.plies());
        } catch (Exception ignored) {
        }
        return o;
    }

    @Override
    public boolean restore(JSONObject snap) {
        if (snap == null) return false;
        String fen = snap.optString("fen", "");
        if (fen.isEmpty()) return false;
        BanqiGame next = new BanqiGame();
        if (!next.loadFen(fen)) return false;
        game = next;
        return true;
    }

    @Override
    public ApplyResult apply(int seat, JSONObject move) {
        ApplyResult r = new ApplyResult();
        r.nextTurn = game.turnSeat();
        if (seat != game.turnSeat()) {
            r.code = "E_OUT_OF_TURN";
            r.message = "还没轮到这一方";
            return r;
        }
        boolean flip = move.optBoolean("flip", false);
        int x = move.optInt("x", -1);
        int y = move.optInt("y", -1);
        int fx = move.optInt("fx", -1);
        int fy = move.optInt("fy", -1);
        int tx = move.optInt("tx", -1);
        int ty = move.optInt("ty", -1);
        boolean ok = flip ? game.flip(x, y) : game.play(fx, fy, tx, ty);
        if (!ok) {
            r.code = "E_ILLEGAL_MOVE";
            r.message = flip ? "这里不能翻子" : "这一手不能走";
            return r;
        }
        BanqiGame.Move last = lastStep();
        r.ok = true;
        r.flip = flip;
        if (flip) {
            r.x = x;
            r.y = y;
        } else {
            r.fx = fx;
            r.fy = fy;
            r.tx = tx;
            r.ty = ty;
        }
        if (last != null) {
            r.piece = last.piece;
            r.captured = last.captured;
        }
        r.seat1Color = game.colorOfSeat(1);
        r.seat2Color = game.colorOfSeat(2);
        r.nextTurn = game.turnSeat();
        r.moveNo = game.plies();
        r.hash = game.positionKey();
        return r;
    }

    @Override
    public boolean applyAck(JSONObject ack, int moveNo, String expectedHash) {
        if (ack == null) return false;
        if (moveNo > 0 && game.plies() != moveNo - 1) return false;
        boolean flip = ack.optBoolean("flip", false);
        boolean ok = flip
                ? game.flipWith(ack.optInt("x", -1), ack.optInt("y", -1),
                ack.optInt("piece", 0))
                : game.play(ack.optInt("fx", -1), ack.optInt("fy", -1),
                ack.optInt("tx", -1), ack.optInt("ty", -1));
        if (!ok) return false;
        return expectedHash == null || expectedHash.isEmpty()
                || expectedHash.equals(game.positionKey());
    }

    @Override
    public int undo(int count) {
        int done = 0;
        for (int i = 0; i < count; i++) {
            if (game.undo() == null) break;
            done++;
        }
        return done;
    }

    @Override
    public JSONObject movePayload(int seat, JSONObject move, String hashBefore) {
        JSONObject p = new JSONObject();
        try {
            boolean flip = move.optBoolean("flip", false);
            p.put("flip", flip);
            if (flip) {
                p.put("x", move.optInt("x", -1));
                p.put("y", move.optInt("y", -1));
            } else {
                p.put("fx", move.optInt("fx", -1));
                p.put("fy", move.optInt("fy", -1));
                p.put("tx", move.optInt("tx", -1));
                p.put("ty", move.optInt("ty", -1));
            }
            if (hashBefore != null && !hashBefore.isEmpty()) p.put("hashBefore", hashBefore);
        } catch (Exception ignored) {
        }
        return p;
    }

    @Override
    public MoveInfo lastMove() {
        BanqiGame.Move m = lastStep();
        if (m == null) return null;
        MoveInfo i = new MoveInfo();
        i.seat = m.seat;
        i.flip = m.flip;
        i.piece = m.piece;
        i.fx = m.x;
        i.fy = m.y;
        if (!m.flip) {
            i.tx = m.tx;
            i.ty = m.ty;
        }
        i.captured = m.captured;
        i.text = game.notation(m);
        return i;
    }

    @Override
    public JSONObject result() {
        if (!game.isOver()) return null;
        int res = game.result();
        String code = res == BanqiGame.RESULT_DRAW ? "draw"
                : res == BanqiGame.RED ? "red" : "black";
        JSONObject o = new JSONObject();
        try {
            o.put("result", code);
            o.put("detail", game.endReason());
            o.put("reason", reasonOf(game.endCause()));
            o.put("moveNo", game.plies());
        } catch (Exception ignored) {
        }
        return o;
    }

    /** 终局原因 → 协议 reason 字段。 */
    private static String reasonOf(int cause) {
        switch (cause) {
            case BanqiGame.END_RESIGN:
                return "resign";
            case BanqiGame.END_TIMEOUT:
                return "timeout";
            case BanqiGame.END_DISCONNECT:
                return "disconnect";
            case BanqiGame.END_NO_MOVES:
                return "no_moves";
            case BanqiGame.END_DRAW:
                return "draw";
            default:
                return "rule";
        }
    }

    @Override
    public JSONObject resultByResign(int resignSeat) {
        game.endByCause(resignSeat, BanqiGame.END_RESIGN);
        return result();
    }

    @Override
    public JSONObject resultByTimeout(int timeoutSeat) {
        game.endByCause(timeoutSeat, BanqiGame.END_TIMEOUT);
        return result();
    }

    @Override
    public JSONObject resultByDisconnect(int lostSeat) {
        game.endByCause(lostSeat, BanqiGame.END_DISCONNECT);
        return result();
    }

    @Override
    public JSONObject scoreResult() {
        return null;
    }

    @Override
    public String colorChar(int seat) {
        return String.valueOf(seat);
    }

    @Override
    public int seatFromChar(String s) {
        if ("1".equals(s)) return 1;
        if ("2".equals(s)) return 2;
        // 兼容旧字段：B 当作先手，W 当作后手
        return "W".equalsIgnoreCase(s) ? 2 : 1;
    }

    @Override
    public String colorName(int seat) {
        int color = game.colorOfSeat(seat);
        if (color == 0) return seat == BanqiGame.SEAT_FIRST ? "先手" : "后手";
        return BanqiGame.colorName(color);
    }

    @Override
    public boolean supportsPass() {
        return false;
    }

    @Override
    public boolean supportsScore() {
        return false;
    }

    /** history 用 push 存放，最新的着法在最前。 */
    private BanqiGame.Move lastStep() {
        List<BanqiGame.Move> history = game.historySnapshot();
        return history.isEmpty() ? null : history.get(0);
    }
}
