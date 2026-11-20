package com.sijia.go.net;

import com.sijia.go.XiangqiGame;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 中国象棋规则适配器：封装 XiangqiGame。 */
public class XiangqiRules implements GameAdapter {
    private XiangqiGame game = new XiangqiGame();

    public XiangqiGame game() {
        return game;
    }

    @Override
    public String id() {
        return XIANGQI;
    }

    @Override
    public String label() {
        return "象棋";
    }

    @Override
    public int boardSize() {
        return XiangqiGame.COLS;
    }

    @Override
    public void reset() {
        game = new XiangqiGame();
    }

    @Override
    public int turn() {
        return game.turn();
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
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((game.positionKey() + "|M=" + moveNo)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString((game.positionKey() + "|M=" + moveNo).hashCode());
        }
    }

    @Override
    public JSONObject snapshot() {
        JSONObject o = new JSONObject();
        try {
            o.put("gameId", id());
            o.put("size", XiangqiGame.COLS);
            o.put("fen", game.fen());
            o.put("toMove", colorChar(game.turn()));
            o.put("result", game.result());
            o.put("endReason", game.endReason());
            JSONArray arr = new JSONArray();
            List<XiangqiGame.Move> moves = new ArrayList<>(game.history());
            Collections.reverse(moves);
            for (XiangqiGame.Move m : moves) {
                JSONArray one = new JSONArray();
                one.put(m.uci());
                one.put(m.fx);
                one.put(m.fy);
                one.put(m.tx);
                one.put(m.ty);
                arr.put(one);
            }
            o.put("moves", arr);
        } catch (Exception ignored) {
        }
        return o;
    }

    @Override
    public boolean restore(JSONObject p) {
        try {
            if (p == null) return false;
            game = new XiangqiGame();
            JSONArray arr = p.optJSONArray("moves");
            if (arr != null && arr.length() > 0) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONArray one = arr.getJSONArray(i);
                    if (one.length() >= 5) {
                        if (!game.play(one.getInt(1), one.getInt(2),
                                one.getInt(3), one.getInt(4))) return false;
                    } else if (!game.playUci(one.getString(0))) {
                        return false;
                    }
                }
                return true;
            }
            String fen = p.optString("fen", "");
            return fen.isEmpty() || game.loadFen(fen);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public ApplyResult apply(int seat, JSONObject move) {
        ApplyResult r = new ApplyResult();
        r.nextTurn = game.turn();
        if (seat != game.turn()) {
            r.code = "E_OUT_OF_TURN";
            r.message = "还没轮到这一方";
            return r;
        }
        int fx = move.optInt("fx", -1);
        int fy = move.optInt("fy", -1);
        int tx = move.optInt("tx", -1);
        int ty = move.optInt("ty", -1);
        if (fx < 0 || fy < 0 || tx < 0 || ty < 0
                || fx >= XiangqiGame.COLS || tx >= XiangqiGame.COLS
                || fy >= XiangqiGame.ROWS || ty >= XiangqiGame.ROWS) {
            r.code = "E_ILLEGAL_MOVE";
            r.message = "落点超出棋盘";
            return r;
        }
        int captured = game.pieceAt(tx, ty) != XiangqiGame.EMPTY ? 1 : 0;
        if (!game.play(fx, fy, tx, ty)) {
            r.code = "E_ILLEGAL_MOVE";
            r.message = game.illegalReason(fx, fy, tx, ty);
            return r;
        }
        r.ok = true;
        r.fx = fx;
        r.fy = fy;
        r.tx = tx;
        r.ty = ty;
        r.captured = captured;
        r.nextTurn = game.turn();
        return r;
    }

    @Override
    public boolean applyAck(JSONObject ack, int moveNo, String expectedHash) {
        return game.play(ack.optInt("fx", -1), ack.optInt("fy", -1),
                ack.optInt("tx", -1), ack.optInt("ty", -1));
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
            int fx = move.optInt("fx", -1);
            int fy = move.optInt("fy", -1);
            int tx = move.optInt("tx", -1);
            int ty = move.optInt("ty", -1);
            p.put("fx", fx);
            p.put("fy", fy);
            p.put("tx", tx);
            p.put("ty", ty);
            p.put("uci", new XiangqiGame.Move(fx, fy, tx, ty).uci());
            if (hashBefore != null && !hashBefore.isEmpty()) p.put("hashBefore", hashBefore);
        } catch (Exception ignored) {
        }
        return p;
    }

    @Override
    public MoveInfo lastMove() {
        List<XiangqiGame.Move> h = game.history();
        if (h.isEmpty()) return null;
        XiangqiGame.Move m = h.get(0);
        MoveInfo i = new MoveInfo();
        i.seat = m.color;
        i.fx = m.fx;
        i.fy = m.fy;
        i.tx = m.tx;
        i.ty = m.ty;
        i.captured = m.captured != XiangqiGame.EMPTY ? 1 : 0;
        i.text = game.notation(m);
        return i;
    }

    @Override
    public JSONObject result() {
        if (!game.isOver()) return null;
        String code = game.result() == XiangqiGame.RESULT_DRAW ? "draw" : colorChar(game.result());
        return result(code, game.endReason(), "rule");
    }

    @Override
    public JSONObject resultByResign(int resignSeat) {
        game.resign(resignSeat);
        int winner = 3 - resignSeat;
        return result(colorChar(winner),
                colorName(resignSeat) + "方认输，" + colorName(winner) + "方胜", "resign");
    }

    @Override
    public JSONObject resultByTimeout(int timeoutSeat) {
        game.resign(timeoutSeat);
        int winner = 3 - timeoutSeat;
        return result(colorChar(winner),
                colorName(timeoutSeat) + "方超时，" + colorName(winner) + "方胜", "timeout");
    }

    @Override
    public JSONObject resultByDisconnect(int lostSeat) {
        game.resign(lostSeat);
        int winner = 3 - lostSeat;
        return result(colorChar(winner),
                colorName(lostSeat) + "方断线，" + colorName(winner) + "方胜", "disconnect");
    }

    @Override
    public JSONObject scoreResult() {
        return null;
    }

    private static JSONObject result(String code, String detail, String reason) {
        JSONObject o = new JSONObject();
        try {
            o.put("result", code);
            o.put("detail", detail);
            o.put("reason", reason);
        } catch (Exception ignored) {
        }
        return o;
    }

    @Override
    public String colorChar(int seat) {
        return seat == XiangqiGame.RED ? "R" : "B";
    }

    @Override
    public int seatFromChar(String s) {
        return "R".equalsIgnoreCase(s) ? XiangqiGame.RED : XiangqiGame.BLACK;
    }

    @Override
    public String colorName(int seat) {
        return seat == XiangqiGame.RED ? "红" : "黑";
    }

    @Override
    public boolean supportsPass() {
        return false;
    }

    @Override
    public boolean supportsScore() {
        return false;
    }
}
