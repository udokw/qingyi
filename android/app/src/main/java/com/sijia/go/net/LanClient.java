package com.sijia.go.net;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;

/** Guest 侧 TCP 连接。 */
public class LanClient {
    private static final String TAG = "LanClient";

    public interface Callback {
        void onMessage(JSONObject msg);

        void onClosed(String reason);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private volatile boolean closed;
    private String host = "";
    private int port;
    private Callback cb;
    private final ArrayBlockingQueue<JSONObject> outQueue = new ArrayBlockingQueue<>(128);
    private Thread writer;

    public void connect(String host, int port, Callback cb) throws IOException {
        this.cb = cb;
        this.host = host;
        this.port = port;
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 6000);
        } catch (IOException e) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
        s.setTcpNoDelay(true);
        socket = s;
        in = new DataInputStream(s.getInputStream());
        out = s.getOutputStream();
        Log.i(TAG, "TCP 已连接 " + host + ":" + port);

        // 写线程：收发解耦，允许在主线程回调里安全调用 send。
        writer = new Thread(() -> {
            while (!closed) {
                JSONObject m;
                try {
                    m = outQueue.take();
                } catch (InterruptedException e) {
                    break;
                }
                try {
                    synchronized (out) {
                        FrameIO.write(out, m);
                    }
                    Log.i(TAG, "已发送 type=" + Proto.type(m));
                } catch (Exception e) {
                    Log.e(TAG, "发送失败 type=" + Proto.type(m), e);
                    close();
                    break;
                }
            }
        }, "lan-client-write");
        writer.setDaemon(true);
        writer.start();

        Thread t = new Thread(() -> {
            String reason = "连接已断开";
            try {
                while (!closed) {
                    JSONObject m = FrameIO.read(in);
                    if (m == null) {
                        reason = "房主已关闭连接";
                        Log.w(TAG, "读到流结束 " + host + ":" + port);
                        break;
                    }
                    Log.i(TAG, "收到 type=" + Proto.type(m));
                    final JSONObject copy = m;
                    main.post(() -> {
                        if (cb != null) cb.onMessage(copy);
                    });
                }
            } catch (Exception e) {
                reason = e.getMessage() == null ? "连接中断" : e.getMessage();
                if (!closed) Log.e(TAG, "读连接异常 " + host + ":" + port, e);
            } finally {
                closed = true;
                if (writer != null) writer.interrupt();
                final String r = reason;
                main.post(() -> {
                    if (cb != null) cb.onClosed(r);
                });
            }
        }, "lan-client-read");
        t.setDaemon(true);
        t.start();
    }

    public boolean isConnected() {
        return !closed && socket != null && socket.isConnected();
    }

    public boolean send(JSONObject msg) {
        if (closed || out == null) {
            Log.w(TAG, "发送失败：连接不可用 type=" + Proto.type(msg) + " host=" + host + ":" + port);
            return false;
        }
        if (!outQueue.offer(msg)) {
            Log.e(TAG, "发送失败：发送队列已满 type=" + Proto.type(msg));
            return false;
        }
        return true;
    }

    public void close() {
        if (closed) return;
        closed = true;
        Log.i(TAG, "主动断开 " + host + ":" + port);
        if (writer != null) writer.interrupt();
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
    }
}
