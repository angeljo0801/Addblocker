package com.addblocker.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class AdBlockVpnService extends VpnService {
    public static final String ACTION_START = "com.addblocker.app.START";
    public static final String ACTION_STOP = "com.addblocker.app.STOP";
    private static final String CHANNEL_ID = "addblocker_vpn";
    private static final int NOTIFICATION_ID = 701;
    private static final String LOCAL_DNS = "10.7.0.2";
    private static final String VPN_ADDR = "10.7.0.1";
    private static final int MAX_LOG_LINES = 300;

    private ParcelFileDescriptor tun;
    private Thread worker;
    private volatile boolean alive;
    private SharedPreferences prefs;
    private volatile Set<String> builtInDomains = Collections.emptySet();
    private final Set<String> loggedThisSession = Collections.synchronizedSet(new HashSet<>());
    private long logGeneration = -1;

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("addblocker", MODE_PRIVATE);
        builtInDomains = loadAssetDomains();
        logGeneration = prefs.getLong("log_generation", 0);
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopVpn();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, buildNotification());
        startVpnIfNeeded();
        return START_STICKY;
    }

    private synchronized void startVpnIfNeeded() {
        if (alive || tun != null) return;
        try {
            Builder builder = new Builder()
                    .setSession("Add Blocker")
                    .setMtu(1500)
                    .addAddress(VPN_ADDR, 32)
                    .addDnsServer(LOCAL_DNS)
                    .addRoute(LOCAL_DNS, 32)
                    .setBlocking(true);
            String excluded = prefs.getString("excluded_apps", "");
            for (String pkg : excluded.split("\\r?\\n")) {
                pkg = pkg.trim();
                if (pkg.isEmpty()) continue;
                try { builder.addDisallowedApplication(pkg); } catch (Exception ignored) {}
            }
            tun = builder.establish();
            if (tun == null) {
                prefs.edit().putBoolean("running", false).apply();
                stopSelf();
                return;
            }
            alive = true;
            prefs.edit().putBoolean("running", true).apply();
            worker = new Thread(this::packetLoop, "AddBlocker-DNS");
            worker.start();
        } catch (Exception e) {
            prefs.edit().putBoolean("running", false).apply();
            stopVpn();
        }
    }

    private void packetLoop() {
        byte[] buffer = new byte[32767];
        try (FileInputStream in = new FileInputStream(tun.getFileDescriptor());
             FileOutputStream out = new FileOutputStream(tun.getFileDescriptor())) {
            while (alive) {
                int len = in.read(buffer);
                if (len <= 0) continue;
                byte[] response = handlePacket(buffer, len);
                if (response != null) {
                    out.write(response);
                    out.flush();
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (alive) stopVpn();
        }
    }

    private byte[] handlePacket(byte[] packet, int len) {
        try {
            if (len < 28) return null;
            int version = (packet[0] >> 4) & 0x0F;
            if (version != 4) return null;
            int ipHeader = (packet[0] & 0x0F) * 4;
            if (ipHeader < 20 || len < ipHeader + 8) return null;
            if ((packet[9] & 0xFF) != 17) return null;

            int dstPort = u16(packet, ipHeader + 2);
            if (dstPort != 53) return null;
            int dnsOffset = ipHeader + 8;
            int dnsLen = len - dnsOffset;
            if (dnsLen < 12) return null;

            String domain = parseQuestionName(packet, dnsOffset, dnsLen);
            if (domain == null || domain.isEmpty()) return null;

            boolean blocked = shouldBlock(domain);
            boolean suspicious = blocked || isSuspiciousDomain(domain);
            recordDomain(domain, blocked, suspicious);

            byte[] dnsResponse;
            if (blocked) {
                dnsResponse = buildNxDomain(packet, dnsOffset, dnsLen);
                long total = prefs.getLong("blocked_count", 0) + 1;
                prefs.edit().putLong("blocked_count", total).apply();
            } else {
                byte[] query = new byte[dnsLen];
                System.arraycopy(packet, dnsOffset, query, 0, dnsLen);
                dnsResponse = resolveUpstream(query);
                if (dnsResponse == null) return null;
            }
            return wrapUdpIpv4Response(packet, ipHeader, dnsResponse);
        } catch (Exception e) {
            return null;
        }
    }

    private void recordDomain(String domain, boolean blocked, boolean suspicious) {
        long currentGeneration = prefs.getLong("log_generation", 0);
        if (currentGeneration != logGeneration) {
            loggedThisSession.clear();
            logGeneration = currentGeneration;
        }
        if (!loggedThisSession.add(domain)) return;

        String flag = blocked ? "B" : (suspicious ? "S" : "N");
        String line = System.currentTimeMillis() + "\t" + flag + "\t" + domain;
        String old = prefs.getString("dns_log", "");
        String combined = old == null || old.isEmpty() ? line : line + "\n" + old;
        String[] lines = combined.split("\\n");
        StringBuilder trimmed = new StringBuilder();
        int limit = Math.min(lines.length, MAX_LOG_LINES);
        for (int i = 0; i < limit; i++) {
            if (i > 0) trimmed.append('\n');
            trimmed.append(lines[i]);
        }
        long seen = prefs.getLong("seen_count", 0) + 1;
        prefs.edit()
                .putString("dns_log", trimmed.toString())
                .putLong("seen_count", seen)
                .apply();
    }

    private boolean isSuspiciousDomain(String domain) {
        String d = domain.toLowerCase(Locale.US);
        String[] tokens = {
                "doubleclick", "googlesyndication", "googleadservices", "adservice", "admob",
                "applovin", "unityads", "unity3dads", "ironsource", "is.com", "chartboost",
                "vungle", "inmobi", "adcolony", "tapjoy", "fyber", "mopub", "pubmatic",
                "rubiconproject", "amazon-adsystem", "adsystem", "adnxs", "criteo",
                "scorecardresearch", "tracking", "tracker", "analytics", "adjust.com",
                "appsflyer", "branch.io", "ads.", ".ads.", "-ads.", ".ad."
        };
        for (String token : tokens) if (d.contains(token)) return true;
        return d.startsWith("ad.") || d.startsWith("ads.") || d.startsWith("adserver.");
    }

    private String parseQuestionName(byte[] data, int dnsOffset, int dnsLen) {
        if (dnsLen < 13) return null;
        int qdCount = u16(data, dnsOffset + 4);
        if (qdCount < 1) return null;
        int p = dnsOffset + 12;
        int end = dnsOffset + dnsLen;
        StringBuilder name = new StringBuilder();
        int labels = 0;
        while (p < end && labels++ < 128) {
            int n = data[p++] & 0xFF;
            if (n == 0) break;
            if ((n & 0xC0) != 0 || n > 63 || p + n > end) return null;
            if (name.length() > 0) name.append('.');
            for (int i = 0; i < n; i++) {
                int c = data[p++] & 0xFF;
                if (c < 33 || c > 126) return null;
                name.append((char)c);
            }
        }
        return name.toString().toLowerCase(Locale.US);
    }

    private boolean shouldBlock(String domain) {
        Set<String> allow = parsePrefsSet(prefs.getString("allow_list", ""));
        if (matches(domain, allow)) return false;
        if (matches(domain, builtInDomains)) return true;
        Set<String> custom = parsePrefsSet(prefs.getString("custom_block", ""));
        return matches(domain, custom);
    }

    private boolean matches(String domain, Set<String> list) {
        String d = domain.toLowerCase(Locale.US);
        for (String rule : list) {
            if (d.equals(rule) || d.endsWith("." + rule)) return true;
        }
        return false;
    }

    private Set<String> parsePrefsSet(String raw) {
        if (raw == null || raw.trim().isEmpty()) return Collections.emptySet();
        Set<String> set = new HashSet<>();
        for (String line : raw.split("\\r?\\n")) {
            String d = normalizeDomain(line);
            if (!d.isEmpty()) set.add(d);
        }
        return set;
    }

    private Set<String> loadAssetDomains() {
        Set<String> set = new HashSet<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(getAssets().open("domains.txt")))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String d = normalizeDomain(line);
                if (!d.isEmpty()) set.add(d);
            }
        } catch (Exception ignored) {}
        return set;
    }

    private String normalizeDomain(String value) {
        String d = value == null ? "" : value.trim().toLowerCase(Locale.US);
        d = d.replaceFirst("^https?://", "");
        if (d.startsWith("*.")) d = d.substring(2);
        int slash = d.indexOf('/');
        if (slash >= 0) d = d.substring(0, slash);
        return d;
    }

    private byte[] buildNxDomain(byte[] packet, int dnsOffset, int dnsLen) {
        int end = questionEnd(packet, dnsOffset, dnsLen);
        if (end < 0) end = dnsOffset + dnsLen;
        int outLen = end - dnsOffset;
        byte[] dns = new byte[outLen];
        System.arraycopy(packet, dnsOffset, dns, 0, outLen);
        int queryFlags = u16(dns, 2);
        int flags = 0x8000 | (queryFlags & 0x0100) | 0x0080 | 0x0003;
        put16(dns, 2, flags);
        put16(dns, 6, 0);
        put16(dns, 8, 0);
        put16(dns, 10, 0);
        return dns;
    }

    private int questionEnd(byte[] data, int dnsOffset, int dnsLen) {
        int p = dnsOffset + 12;
        int end = dnsOffset + dnsLen;
        int labels = 0;
        while (p < end && labels++ < 128) {
            int n = data[p++] & 0xFF;
            if (n == 0) return p + 4 <= end ? p + 4 : -1;
            if ((n & 0xC0) != 0 || n > 63 || p + n > end) return -1;
            p += n;
        }
        return -1;
    }

    private byte[] resolveUpstream(byte[] query) {
        byte[] receive = new byte[4096];
        String[] servers = {"1.1.1.1", "8.8.8.8"};
        for (String server : servers) {
            DatagramSocket socket = null;
            try {
                socket = new DatagramSocket();
                protect(socket);
                socket.setSoTimeout(2500);
                DatagramPacket req = new DatagramPacket(query, query.length, InetAddress.getByName(server), 53);
                socket.send(req);
                DatagramPacket resp = new DatagramPacket(receive, receive.length);
                socket.receive(resp);
                byte[] out = new byte[resp.getLength()];
                System.arraycopy(resp.getData(), resp.getOffset(), out, 0, resp.getLength());
                return out;
            } catch (SocketTimeoutException ignored) {
            } catch (Exception ignored) {
            } finally {
                if (socket != null) socket.close();
            }
        }
        return null;
    }

    private byte[] wrapUdpIpv4Response(byte[] request, int ipHeaderLen, byte[] dns) {
        int total = 20 + 8 + dns.length;
        byte[] out = new byte[total];
        ByteBuffer b = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN);
        b.put((byte)0x45);
        b.put((byte)0);
        b.putShort((short)total);
        b.putShort((short)0);
        b.putShort((short)0x4000);
        b.put((byte)64);
        b.put((byte)17);
        b.putShort((short)0);
        b.put(request, 16, 4);
        b.put(request, 12, 4);
        int sourcePort = u16(request, ipHeaderLen + 2);
        int destPort = u16(request, ipHeaderLen);
        b.putShort((short)sourcePort);
        b.putShort((short)destPort);
        b.putShort((short)(8 + dns.length));
        b.putShort((short)0);
        b.put(dns);
        int ipChecksum = ipv4Checksum(out, 0, 20);
        put16(out, 10, ipChecksum);
        return out;
    }

    private int ipv4Checksum(byte[] data, int offset, int length) {
        long sum = 0;
        int i = offset;
        while (length > 1) {
            sum += ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
            i += 2;
            length -= 2;
        }
        if (length > 0) sum += (data[i] & 0xFF) << 8;
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (int)(~sum) & 0xFFFF;
    }

    private int u16(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private void put16(byte[] data, int offset, int value) {
        data[offset] = (byte)((value >> 8) & 0xFF);
        data[offset + 1] = (byte)(value & 0xFF);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Protección Add Blocker", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Mantiene activo el filtro DNS local");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setContentTitle("Add Blocker activo")
                .setContentText("Bloqueando anuncios y registrando dominios DNS")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private synchronized void stopVpn() {
        alive = false;
        prefs.edit().putBoolean("running", false).apply();
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        if (tun != null) {
            try { tun.close(); } catch (Exception ignored) {}
            tun = null;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onRevoke() {
        stopVpn();
        super.onRevoke();
    }

    @Override public void onDestroy() {
        alive = false;
        prefs.edit().putBoolean("running", false).apply();
        if (tun != null) {
            try { tun.close(); } catch (Exception ignored) {}
            tun = null;
        }
        super.onDestroy();
    }
}
