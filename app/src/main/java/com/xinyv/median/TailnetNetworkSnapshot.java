package com.xinyv.median;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.List;

/** Supplies Android-authorized network state to libtailscale without netlink access. */
final class TailnetNetworkSnapshot {
    private TailnetNetworkSnapshot() {}

    static Snapshot capture(Context context) {
        if (context == null) throw new NullPointerException("context");
        return new Snapshot(interfacesJson(), defaultInterfaceName(context));
    }

    private static String interfacesJson() {
        JSONArray interfaces = new JSONArray();
        try {
            List<NetworkInterface> networkInterfaces =
                    Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface networkInterface : networkInterfaces) {
                try {
                    JSONObject value = new JSONObject();
                    value.put("name", networkInterface.getName());
                    value.put("index", networkInterface.getIndex());
                    value.put("mtu", networkInterface.getMTU());
                    value.put("up", networkInterface.isUp());
                    value.put("broadcast", !networkInterface.isLoopback());
                    value.put("loopback", networkInterface.isLoopback());
                    value.put("pointToPoint", networkInterface.isPointToPoint());
                    value.put("multicast", networkInterface.supportsMulticast());
                    JSONArray addresses = new JSONArray();
                    for (InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()) {
                        if (interfaceAddress.getAddress() == null) continue;
                        String hostAddress = interfaceAddress.getAddress().getHostAddress();
                        if (hostAddress == null || hostAddress.length() == 0) continue;
                        JSONObject address = new JSONObject();
                        address.put("ip", hostAddress);
                        address.put("prefixLen", interfaceAddress.getNetworkPrefixLength());
                        addresses.put(address);
                    }
                    value.put("addrs", addresses);
                    interfaces.put(value);
                } catch (SocketException | JSONException ignored) {
                    // One inaccessible interface must not hide the remaining usable interfaces.
                }
            }
        } catch (SocketException ignored) {
            // The caller rejects an empty snapshot rather than falling back to netlink.
        }
        return interfaces.toString();
    }

    private static String defaultInterfaceName(Context context) {
        ConnectivityManager manager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return "";
        Network network = manager.getActiveNetwork();
        if (network == null) return "";
        LinkProperties properties = manager.getLinkProperties(network);
        if (properties == null || properties.getInterfaceName() == null) return "";
        return properties.getInterfaceName();
    }

    static final class Snapshot {
        final String interfacesJson;
        final String defaultInterfaceName;

        Snapshot(String interfacesJson, String defaultInterfaceName) {
            this.interfacesJson = interfacesJson;
            this.defaultInterfaceName = defaultInterfaceName;
        }
    }
}