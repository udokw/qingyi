package com.sijia.go.net;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** TCP 帧编解码：4 字节大端长度 + UTF-8 JSON。 */
public final class FrameIO {
    private FrameIO() {}

    public static void write(OutputStream out, JSONObject msg) throws IOException {
        byte[] body = msg.toString().getBytes(StandardCharsets.UTF_8);
        if (body.length > Proto.MAX_FRAME) throw new IOException("frame too large: " + body.length);
        DataOutputStream d = new DataOutputStream(out);
        d.writeInt(body.length);
        d.write(body);
        d.flush();
    }

    /** 返回 null 表示对端正常关闭。 */
    public static JSONObject read(InputStream in) throws IOException {
        DataInputStream d = new DataInputStream(in);
        int len;
        try {
            len = d.readInt();
        } catch (EOFException e) {
            return null;
        }
        if (len <= 0 || len > Proto.MAX_FRAME) throw new IOException("bad frame length: " + len);
        byte[] body = new byte[len];
        d.readFully(body);
        try {
            return new JSONObject(new String(body, StandardCharsets.UTF_8));
        } catch (org.json.JSONException e) {
            throw new IOException("bad json frame: " + e.getMessage(), e);
        }
    }
}
