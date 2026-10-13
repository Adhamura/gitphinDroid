package io.github.adhamura.gitphindroid.net;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Answers mDNS queries for "name.local" with the phone's IPv4 address, and registers an
 *  NSD "_http._tcp" service. Never throws to the caller. */
public final class MdnsResponder {
    private static final String TAG = "GitphinMdns";
    private static final int PORT = 5353;
    private static final String GROUP = "224.0.0.251";

    private final Context ctx;
    private final String host; // "gitphin.local", lower case
    private final int httpPort;
    private volatile boolean running;
    private Thread thread;
    private MulticastSocket socket;
    private WifiManager.MulticastLock lock;
    private NsdManager nsd;
    private NsdManager.RegistrationListener nsdListener;
    private boolean nsdRegistered;
    private final Set<String> joined = new HashSet<>();
    private InetAddress group;

    public MdnsResponder(Context c, String name, int httpPort) {
        ctx = c.getApplicationContext();
        host = name.toLowerCase(java.util.Locale.ROOT) + ".local";
        this.httpPort = httpPort;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                lock = wm.createMulticastLock("gitphin:mdns");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "multicast lock: " + e);
        }
        thread = new Thread(this::loop, "mdns");
        thread.setDaemon(true);
        thread.start();
        registerNsd();
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        unregisterNsd();
        MulticastSocket s = socket;
        if (s != null) s.close();
        if (thread != null) {
            thread.interrupt();
            try { thread.join(1500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            thread = null;
        }
        try {
            if (lock != null && lock.isHeld()) lock.release();
        } catch (Exception ignored) { }
        lock = null;
    }

    // ---------------------------------------------------------------- NSD

    private void registerNsd() {
        try {
            nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
            if (nsd == null) return;
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName("Gitphin");
            info.setServiceType("_http._tcp");
            info.setPort(httpPort);
            nsdListener = new NsdManager.RegistrationListener() {
                @Override public void onRegistrationFailed(NsdServiceInfo i, int code) {
                    Log.w(TAG, "NSD registration failed: " + code);
                }
                @Override public void onUnregistrationFailed(NsdServiceInfo i, int code) { }
                @Override public void onServiceRegistered(NsdServiceInfo i) { }
                @Override public void onServiceUnregistered(NsdServiceInfo i) { }
            };
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, nsdListener);
            nsdRegistered = true;
        } catch (Throwable t) {
            Log.w(TAG, "NSD: " + t);
        }
    }

    private void unregisterNsd() {
        try {
            if (nsd != null && nsdListener != null && nsdRegistered) nsd.unregisterService(nsdListener);
        } catch (Throwable ignored) { }
        nsdRegistered = false;
        nsdListener = null;
    }

    // ---------------------------------------------------------------- socket loop

    private static boolean wantedInterface(NetworkInterface ni) {
        try {
            if (!ni.isUp() || ni.isLoopback() || !ni.supportsMulticast()) return false;
        } catch (Exception e) {
            return false;
        }
        String n = ni.getName();
        return n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan")
                || n.startsWith("softap") || n.startsWith("eth");
    }

    private static Inet4Address ipv4Of(NetworkInterface ni) {
        for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
            if (ia.getAddress() instanceof Inet4Address && !ia.getAddress().isLoopbackAddress()) {
                return (Inet4Address) ia.getAddress();
            }
        }
        return null;
    }

    /** Joins the group on every Wi-Fi/hotspot interface not joined yet. */
    private void joinAll() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!wantedInterface(ni)) continue;
                Inet4Address ip = ipv4Of(ni);
                if (ip == null) continue;
                String key = ni.getName() + "/" + ip.getHostAddress();
                if (joined.contains(key)) continue;
                try {
                    socket.joinGroup(new InetSocketAddress(group, PORT), ni);
                    joined.add(key);
                    announce(ni, ip);
                } catch (Exception e) {
                    Log.w(TAG, "join " + ni.getName() + ": " + e);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "interfaces: " + e);
        }
    }

    private void loop() {
        try {
            group = InetAddress.getByName(GROUP);
            socket = new MulticastSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(PORT));
            socket.setSoTimeout(1000);
            socket.setTimeToLive(255);
            joinAll();
            byte[] buf = new byte[9000];
            long lastJoin = System.currentTimeMillis();
            while (running) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    socket.receive(p);
                    handle(p);
                } catch (java.net.SocketTimeoutException ignored) {
                    // check the network again below
                } catch (Exception e) {
                    if (!running) break;
                    Log.w(TAG, "packet: " + e);
                }
                long now = System.currentTimeMillis();
                if (now - lastJoin > 15000) {
                    lastJoin = now;
                    joinAll();
                }
            }
        } catch (Throwable t) {
            if (running) Log.w(TAG, "mDNS stopped: " + t);
        } finally {
            try { if (socket != null) socket.close(); } catch (Throwable ignored) { }
            socket = null;
            joined.clear();
        }
    }

    private void announce(final NetworkInterface ni, final Inet4Address ip) {
        // Two unsolicited answers, one second apart, from a helper thread.
        Thread t = new Thread(() -> {
            for (int i = 0; i < 2 && running; i++) {
                try {
                    sendTo(buildAnswer(0, null, ip), group, PORT, ni);
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    Log.w(TAG, "announce: " + e);
                }
            }
        }, "mdns-announce");
        t.setDaemon(true);
        t.start();
    }

    private synchronized void sendTo(byte[] data, InetAddress to, int port, NetworkInterface ni) throws Exception {
        MulticastSocket s = socket;
        if (s == null) return;
        if (ni != null && to.isMulticastAddress()) s.setNetworkInterface(ni);
        s.send(new DatagramPacket(data, data.length, to, port));
    }

    // ---------------------------------------------------------------- DNS

    private void handle(DatagramPacket p) throws Exception {
        byte[] d = p.getData();
        int len = p.getLength();
        if (len < 12) return;
        int id = u16(d, 0), flags = u16(d, 2), qd = u16(d, 4);
        if ((flags & 0x8000) != 0) return;      // an answer (ours or another host's)
        if ((flags & 0x7800) != 0) return;      // not a standard query
        int[] pos = {12};
        boolean match = false;
        for (int i = 0; i < qd; i++) {
            String name = readName(d, len, pos);
            if (name == null || pos[0] + 4 > len) return;
            int type = u16(d, pos[0]);
            int cls = u16(d, pos[0] + 2) & 0x7FFF;
            pos[0] += 4;
            if (name.equalsIgnoreCase(host) && (type == 1 || type == 255) && cls == 1) {
                match = true;
                break;
            }
        }
        if (!match) return;

        InetAddress from = p.getAddress();
        NetworkInterface ni = interfaceFor(from);
        Inet4Address ip = ni != null ? ipv4Of(ni) : null;
        if (ip == null) {
            ni = firstInterface();
            ip = ni != null ? ipv4Of(ni) : null;
        }
        if (ip == null) return;
        if (p.getPort() != PORT) {
            // legacy unicast resolver: answer to the sender, with id and question
            sendTo(buildAnswer(id, encodeQuestion(), ip), from, p.getPort(), null);
        } else {
            sendTo(buildAnswer(0, null, ip), group, PORT, ni);
        }
    }

    private byte[] encodeQuestion() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        writeName(o);
        o.write(0); o.write(1); // A
        o.write(0); o.write(1); // IN
        return o.toByteArray();
    }

    private void writeName(ByteArrayOutputStream o) {
        for (String label : host.split("\\.")) {
            byte[] b = label.getBytes(java.nio.charset.Charset.forName("UTF-8"));
            o.write(b.length);
            o.write(b, 0, b.length);
        }
        o.write(0);
    }

    /** question != null: legacy unicast reply (includes the question and the query id). */
    private byte[] buildAnswer(int id, byte[] question, Inet4Address ip) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(id >> 8); o.write(id);
        o.write(0x84); o.write(0x00);
        o.write(0); o.write(question != null ? 1 : 0);
        o.write(0); o.write(1);
        o.write(0); o.write(0);
        o.write(0); o.write(0);
        if (question != null) o.write(question, 0, question.length);
        writeName(o);
        o.write(0); o.write(1);       // A
        o.write(0x80); o.write(0x01); // IN, cache flush
        o.write(0); o.write(0); o.write(0); o.write(120);
        o.write(0); o.write(4);
        byte[] a = ip.getAddress();
        o.write(a, 0, 4);
        return o.toByteArray();
    }

    /** Reads a (possibly compressed) name; pos[0] ends after the name in the packet. */
    private static String readName(byte[] d, int len, int[] pos) {
        StringBuilder sb = new StringBuilder();
        int p = pos[0];
        int end = -1;
        for (int hops = 0; hops < 20; ) {
            if (p >= len) return null;
            int l = d[p] & 0xFF;
            if (l == 0) {
                p++;
                if (end < 0) end = p;
                pos[0] = end;
                return sb.toString();
            }
            if ((l & 0xC0) == 0xC0) {
                if (p + 1 >= len) return null;
                if (end < 0) end = p + 2;
                p = ((l & 0x3F) << 8) | (d[p + 1] & 0xFF);
                hops++;
                continue;
            }
            if ((l & 0xC0) != 0 || p + 1 + l > len) return null;
            if (sb.length() > 0) sb.append('.');
            sb.append(new String(d, p + 1, l, java.nio.charset.Charset.forName("UTF-8")));
            p += 1 + l;
        }
        return null;
    }

    private static int u16(byte[] d, int i) { return ((d[i] & 0xFF) << 8) | (d[i + 1] & 0xFF); }

    // ---------------------------------------------------------------- interfaces

    /** The wanted interface whose IPv4 subnet holds the sender, or null. */
    private static NetworkInterface interfaceFor(InetAddress from) {
        if (!(from instanceof Inet4Address)) return null;
        try {
            byte[] f = from.getAddress();
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!wantedInterface(ni)) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (!(ia.getAddress() instanceof Inet4Address)) continue;
                    int bits = ia.getNetworkPrefixLength();
                    byte[] m = ia.getAddress().getAddress();
                    boolean same = true;
                    for (int i = 0; i < 4 && same; i++) {
                        int mask = bits >= 8 * (i + 1) ? 0xFF : bits <= 8 * i ? 0 : (0xFF << (8 - (bits - 8 * i))) & 0xFF;
                        if ((f[i] & mask) != (m[i] & mask)) same = false;
                    }
                    if (same) return ni;
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static NetworkInterface firstInterface() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (wantedInterface(ni) && ipv4Of(ni) != null) return ni;
            }
        } catch (Exception ignored) { }
        return null;
    }
}
