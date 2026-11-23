package cz.ideamirror.app;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** UDP LAN discovery of boards (presenter side). Pure Java; never sends any secret. */
final class DiscoveryClient {
    private DiscoveryClient() {}

    /**
     * Prefers the address the reply actually came from: a multi-homed board (Ethernet + Wi-Fi + hotspot)
     * may advertise an address that is not reachable from this network.
     */
    static String preferredIp(String sourceIp, String advertisedIp) {
        if (PairCode.isValidHost(sourceIp)) return sourceIp;
        if (PairCode.isValidHost(advertisedIp)) return advertisedIp;
        return null;
    }

    static List<InetAddress> broadcastTargets() {
        List<InetAddress> targets = new ArrayList<>();
        try { targets.add(InetAddress.getByName("255.255.255.255")); } catch (Exception ignored) {}
        try {
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            while (all != null && all.hasMoreElements()) {
                NetworkInterface n = all.nextElement();
                if (!n.isUp() || n.isLoopback()) continue;
                for (InterfaceAddress ia : n.getInterfaceAddresses()) {
                    InetAddress b = ia.getBroadcast();
                    if (b instanceof Inet4Address && !targets.contains(b)) targets.add(b);
                }
            }
        } catch (Exception ignored) {}
        return targets;
    }

    /** Returns offers keyed by fingerprint (one entry per board), waiting the full timeout. */
    static List<DiscoveryProtocol.Offer> discover(int timeoutMs, List<InetAddress> targets, int port) {
        Map<String, DiscoveryProtocol.Offer> found = new LinkedHashMap<>();
        byte[] req = DiscoveryProtocol.REQUEST.getBytes(StandardCharsets.UTF_8);
        try (DatagramSocket udp = new DatagramSocket()) {
            udp.setBroadcast(true);
            long end = System.currentTimeMillis() + Math.max(300, timeoutMs);
            long nextSend = 0;
            int rounds = 0;
            byte[] buf = new byte[512];
            while (true) {
                long now = System.currentTimeMillis();
                if (now >= end) break;
                if (now >= nextSend && rounds < 3) {
                    for (InetAddress t : targets) {
                        try { udp.send(new DatagramPacket(req, req.length, t, port)); } catch (Exception ignored) {}
                    }
                    rounds++;
                    nextSend = now + Math.max(200, timeoutMs / 3);
                }
                int wait = (int) Math.max(1, Math.min(end, nextSend) - now);
                udp.setSoTimeout(Math.min(wait, 250));
                try {
                    DatagramPacket resp = new DatagramPacket(buf, buf.length);
                    udp.receive(resp);
                    String text = new String(resp.getData(), resp.getOffset(), resp.getLength(), StandardCharsets.UTF_8).trim();
                    DiscoveryProtocol.Offer o = DiscoveryProtocol.parseOffer(text);
                    if (o == null) continue;
                    String src = resp.getAddress() instanceof Inet4Address ? resp.getAddress().getHostAddress() : null;
                    String ip = preferredIp(src, o.ip);
                    if (ip == null) continue;
                    found.put(o.fp, new DiscoveryProtocol.Offer(o.name, o.port, o.fp, ip));
                } catch (SocketTimeoutException ignored) {
                    // keep waiting until the deadline (0.6.x stopped at the first 250 ms of silence)
                }
            }
        } catch (Exception ignored) {}
        return new ArrayList<>(found.values());
    }

    static List<DiscoveryProtocol.Offer> discover(int timeoutMs) {
        return discover(timeoutMs, broadcastTargets(), DiscoveryProtocol.DISCOVERY_PORT);
    }

    static DiscoveryProtocol.Offer findByFingerprint(String fp, int timeoutMs) {
        for (DiscoveryProtocol.Offer o : discover(timeoutMs)) if (o.fp.equals(fp)) return o;
        return null;
    }
}
