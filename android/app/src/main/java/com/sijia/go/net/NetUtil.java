package com.sijia.go.net;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;

public final class NetUtil {
    private NetUtil() {}

    /** 取本机局域网 IPv4，优先 192.168 / 10. / 172.16-31。 */
    public static String localIpv4(Context ctx) {
        String fallback = null;
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface ni : Collections.list(nis)) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    String ip = a.getHostAddress();
                    if (ip == null) continue;
                    if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.16.")
                            || ip.startsWith("172.17.") || ip.startsWith("172.18.") || ip.startsWith("172.19.")
                            || ip.startsWith("172.2") || ip.startsWith("172.30.") || ip.startsWith("172.31.")) {
                        return ip;
                    }
                    if (fallback == null) fallback = ip;
                }
            }
        } catch (Exception ignored) {
        }
        if (fallback != null) return fallback;
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                int ip = wm.getConnectionInfo().getIpAddress();
                if (ip != 0) {
                    return String.format("%d.%d.%d.%d", ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >> 24) & 0xff);
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }
}
