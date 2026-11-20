package com.sijia.go.net;

import org.json.JSONObject;

/**
 * 棋种规则适配器：把房间/传输/计时与具体棋种解耦。
 * 颜色统一使用座位号：1 = 先手（围棋黑 / 象棋红），2 = 后手（围棋白 / 象棋黑）。
 */
public interface GameAdapter {
    String GO = "go";
    String XIANGQI = "xiangqi";
    String GOMOKU = "gomoku";
    String BANQI = "banqi";

    static GameAdapter create(String id) {
        if (XIANGQI.equals(id)) return new XiangqiRules();
        if (GOMOKU.equals(id)) return new GomokuRules();
        if (BANQI.equals(id)) return new BanqiRules();
        return new GoRules();
    }

    /** 落子裁决结果。 */
    class ApplyResult {
        public boolean ok;
        public String code = "";
        public String message = "";
        /** 棋种自定义的落子字段，供 move_ack 回传。 */
        public boolean pass;
        public int x = -1, y = -1;
        public int fx = -1, fy = -1, tx = -1, ty = -1;
        /** 暗棋：翻子标记与翻出/移动的棋子编码。 */
        public boolean flip;
        public int piece;
        /** 暗棋：首翻后两位座位的颜色（1 红 / 2 黑 / 0 未定）。 */
        public int seat1Color;
        public int seat2Color;
        public int captured;
        public int nextTurn;
        public int moveNo;
        public String hash = "";
    }

    /** 供 UI 显示的最后一手。 */
    class MoveInfo {
        public int seat;
        public int fx = -1, fy = -1, tx = -1, ty = -1;
        public boolean pass;
        public boolean flip;
        public int piece;
        public int captured;
        public String text = "";
    }

    String id();

    String label();

    int boardSize();

    void reset();

    /** 行棋方座位号：1 / 2。 */
    int turn();

    int moveCount();

    boolean isOver();

    String hash(int moveNo);

    JSONObject snapshot();

    boolean restore(JSONObject snap);

    /** 在已校验手数/哈希之后落子。move 里的字段与线协议一致（顶层）。 */
    ApplyResult apply(int seat, JSONObject move);

    /** 客机镜像房主的落子。 */
    boolean applyAck(JSONObject ack, int moveNo, String expectedHash);

    int undo(int count);

    /** 客机构造 move 消息：补全棋种字段与 hashBefore。 */
    JSONObject movePayload(int seat, JSONObject move, String hashBefore);

    MoveInfo lastMove();

    /** 棋规已自动判定胜负时返回结果，否则 null。 */
    JSONObject result();

    JSONObject resultByResign(int resignSeat);

    JSONObject resultByTimeout(int timeoutSeat);

    /** 断线超过宽限期判负。 */
    JSONObject resultByDisconnect(int lostSeat);

    /** 围棋数子；不支持返回 null。 */
    JSONObject scoreResult();

    String colorChar(int seat);

    int seatFromChar(String s);

    String colorName(int seat);

    boolean supportsPass();

    boolean supportsScore();

    default void setKomi(double komi) {
    }

    default double komi() {
        return 0;
    }
}
