package cz.ideamirror.app;

import java.util.Locale;

/** Pure-Java IPv4 subnet and interface ranking helpers (tested in CoreTest). */
final class NetUtil {
    static final String TRANSPORT_WIFI = "Wi-Fi";
    static final String TRANSPORT_ETHERNET = "Ethernet";
    static final String TRANSPORT_CELLULAR = "Mobilní";
    static final String TRANSPORT_VPN = "VPN";
    static final String TRANSPORT_OTHER = "Síť";

    static long parseIpv4(String ip) {
        if (ip == null) return -1L;
        String[] parts = ip.trim().split("\\.", -1);
        if (parts.length != 4) return -1L;
        long value = 0;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) return -1L;
            int n;
            try { n = Integer.parseInt(part); } catch (NumberFormatException e) { return -1L; }
            if (n < 0 || n > 255) return -1L;
            value = (value << 8) | n;
        }
        return value;
    }

    static boolean isPrivate(String ip) {
        long v = parseIpv4(ip);
        if (v < 0) return false;
        int a = (int) ((v >>> 24) & 0xFF);
        int b = (int) ((v >>> 16) & 0xFF);
        return a == 10 || (a == 192 && b == 168) || (a == 172 && b >= 16 && b <= 31);
    }

    static boolean sameSubnet(String ipA, int prefixLen, String ipB) {
        if (prefixLen < 1 || prefixLen > 32) return false;
        long a = parseIpv4(ipA);
        long b = parseIpv4(ipB);
        if (a < 0 || b < 0) return false;
        long mask = prefixLen == 32 ? 0xFFFFFFFFL : ((0xFFFFFFFFL << (32 - prefixLen)) & 0xFFFFFFFFL);
        return (a & mask) == (b & mask);
    }

    static boolean isVirtualOrHotspotIface(String iface) {
        if (iface == null || iface.isEmpty()) return false;
        String low = iface.toLowerCase(Locale.ROOT);
        return low.startsWith("tun") || low.startsWith("tap") || low.startsWith("p2p")
            || low.startsWith("ap") || low.startsWith("softap") || low.startsWith("swlan")
            || low.startsWith("rndis") || low.startsWith("usb") || low.startsWith("rmnet")
            || low.startsWith("ccmni") || low.startsWith("dummy") || low.startsWith("docker")
            || low.startsWith("veth") || low.startsWith("virbr") || low.startsWith("br")
            || low.contains("p2p");
    }

    static boolean isStandardLanIface(String iface) {
        if (iface == null || iface.isEmpty()) return false;
        String low = iface.toLowerCase(Locale.ROOT);
        if (isVirtualOrHotspotIface(low)) return false;
        return low.startsWith("wlan") || low.startsWith("eth") || low.startsWith("en") || low.startsWith("wl");
    }

    static String guessTransportFromIface(String iface) {
        if (iface == null || iface.isEmpty()) return TRANSPORT_OTHER;
        String low = iface.toLowerCase(Locale.ROOT);
        if (low.startsWith("tun") || low.startsWith("tap") || low.startsWith("ppp") || low.startsWith("wg")) return TRANSPORT_VPN;
        if (low.startsWith("rmnet") || low.startsWith("ccmni")) return TRANSPORT_CELLULAR;
        if (low.startsWith("p2p") || low.contains("p2p")) return "Wi-Fi Direct";
        if (low.startsWith("ap") || low.startsWith("softap") || low.startsWith("swlan")) return "Hotspot";
        if (low.startsWith("rndis") || low.startsWith("usb")) return "USB síť";
        if (low.startsWith("wlan") || low.startsWith("wl")) return TRANSPORT_WIFI;
        if (low.startsWith("eth") || low.startsWith("en")) return TRANSPORT_ETHERNET;
        return TRANSPORT_OTHER;
    }

    /**
     * Lower rank = higher priority for LAN mirroring.
     * Prefers active ConnectivityManager Wi-Fi/Ethernet interfaces over internal/hotspot/P2P/VPN interfaces.
     */
    static int rankEndpoint(String ip, String iface, String transport, boolean active, boolean hasDefaultRoute) {
        boolean priv = isPrivate(ip);
        boolean virt = isVirtualOrHotspotIface(iface);
        boolean wifiOrEth = TRANSPORT_WIFI.equals(transport) || TRANSPORT_ETHERNET.equals(transport);
        boolean vpn = TRANSPORT_VPN.equals(transport);
        boolean cell = TRANSPORT_CELLULAR.equals(transport);

        if (vpn) return 8;
        if (cell) return 9;
        if (active && wifiOrEth && !virt && priv) return 0;
        if (wifiOrEth && hasDefaultRoute && !virt && priv) return 1;
        if (wifiOrEth && !virt && priv) return 2;
        if (priv && isStandardLanIface(iface)) return 3;
        if (active && wifiOrEth && !virt) return 4;
        if (priv && !virt) return 5;
        if (priv) return 6;
        if (!virt) return 7;
        return 10;
    }

    static String formatCidr(String ip, int prefixLen) {
        return prefixLen > 0 && prefixLen <= 32 ? ip + "/" + prefixLen : ip;
    }

    static String formatEndpoint(String ip, int prefixLen, String iface, String transport, boolean active) {
        StringBuilder sb = new StringBuilder();
        if (transport != null && !transport.isEmpty()) sb.append(transport).append(" · ");
        sb.append(formatCidr(ip, prefixLen));
        if (iface != null && !iface.isEmpty()) sb.append(" (").append(iface).append(")");
        if (active) sb.append(" [aktivní]");
        return sb.toString();
    }
}
