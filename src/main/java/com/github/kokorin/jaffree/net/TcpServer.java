package com.github.kokorin.jaffree.net;

import com.github.kokorin.jaffree.util.Consumer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;

public abstract class TcpServer implements Runnable {
    private final Consumer<Integer> portConsumer;

    public TcpServer(Consumer<Integer> portConsumer) {
        this.portConsumer = portConsumer;
    }

    @Override
    public final void run() {
        ServerSocket serverSocket = null;
        try {
            serverSocket = allocateLoopbackSocket();

            portConsumer.consume(serverSocket.getLocalPort());

            serve(serverSocket);
        } catch (IOException e) {
            throw new RuntimeException("Failed to serve via socket " + serverSocket, e);
        }
    }

    public abstract void serve(ServerSocket serverSocket) throws IOException;

    protected static ServerSocket allocateLoopbackSocket() throws IOException {
        return new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
    }
}
