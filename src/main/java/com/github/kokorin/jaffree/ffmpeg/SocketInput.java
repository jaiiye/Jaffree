/*
 *    Copyright  2019 Denis Kokorin
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 */

package com.github.kokorin.jaffree.ffmpeg;

import com.github.kokorin.jaffree.net.TcpServer;
import com.github.kokorin.jaffree.util.Consumer;

import java.io.IOException;
import java.net.ServerSocket;

public abstract class SocketInput<T extends SocketInput<T>> extends BaseInput<T> implements Input {
    private final String protocol;
    private final String suffix;

    public SocketInput(String protocol) {
        this(protocol, "");
    }

    public SocketInput(String protocol, String suffix) {
        this.protocol = protocol;
        this.suffix = suffix;
    }

    @Override
    public final Runnable helperThread() {
        final Negotiator negotiator = negotiator();

        return new TcpServer(portConsumer()) {
            @Override
            public void serve(ServerSocket serverSocket) throws IOException {
                negotiator.negotiateAndClose(serverSocket);
            }
        };
    }

    @Override
    public final T setInput(String input) {
        throw new RuntimeException("SocketInput input can't be changed");
    }

    /**
     * Creates {@link Negotiator}.
     *
     * @return negotiator
     */
    // TODO: make protected?
    abstract Negotiator negotiator();

    //TODO synchronization!
    private Consumer<Integer> portConsumer() {
        return new Consumer<Integer>() {
            @Override
            public void consume(Integer port) {
                SocketInput.super.setInput(protocol + "://127.0.0.1:" + port + suffix);
            }
        };
    }

    /**
     * {@link Negotiator} is capable of integrating with ffmpeg via Socket-based connection.
     */
    // TODO: make protected?
    abstract class Negotiator {
        /**
         * Negotiator <b>must</b> close passed in {@code ServerSocket}
         *
         * @param serverSocket socket to communicate
         * @throws IOException
         */
        abstract void negotiateAndClose(ServerSocket serverSocket) throws IOException;
    }
}
