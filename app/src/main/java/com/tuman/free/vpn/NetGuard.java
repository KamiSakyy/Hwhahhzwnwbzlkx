package com.tuman.free.vpn;

import java.net.DatagramSocket;
import java.net.Socket;

/**
 * Lets the tunnel keep its own sockets out of the VPN.
 * Implemented by {@link TunnelVpnService} (which can call {@code VpnService.protect}).
 * A no-op instance is used by the auto-detector, which runs without a VPN.
 */
public interface NetGuard {

    boolean protectSocket(Socket socket);

    boolean protectDatagram(DatagramSocket socket);

    NetGuard NOOP = new NetGuard() {
        @Override
        public boolean protectSocket(Socket socket) {
            return true;
        }

        @Override
        public boolean protectDatagram(DatagramSocket socket) {
            return true;
        }
    };
}
