/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.portal.feishu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 纯手工 WebSocket 客户端（RFC 6455 帧协议）
 *
 * <p>不使用任何 WebSocket 库，直接用 Java Socket + 手动帧解析。</p>
 *
 * <p>关键设计：</p>
 * <ul>
 *   <li>HTTP Upgrade 握手后切换为二进制帧模式</li>
 *   <li>不请求 permessage-deflate 扩展（与飞书 SDK 行为一致，SDK 的 OkHttp 不支持扩展）</li>
 *   <li>二进制帧（opcode=0x2）交给回调处理</li>
 *   <li>Ping/Pong 自动回复</li>
 *   <li>断线自动重连</li>
 * </ul>
 *
 * @author noear 2026/5/10 created
 */
public class RawWsClient {
    private static final Logger LOG = LoggerFactory.getLogger(RawWsClient.class);

    // --- 回调接口 ---

    public interface Listener {
        /** 收到二进制帧 */
        void onBinary(ByteBuffer data);
        /** 连接打开 */
        void onOpen();
        /** 连接关闭 */
        void onClose(int code, String reason);
        /** 连接错误 */
        void onError(Exception ex);
    }

    // --- WebSocket Opcodes ---
    private static final int OPCODE_CONTINUATION = 0x0;
    private static final int OPCODE_TEXT = 0x1;
    private static final int OPCODE_BINARY = 0x2;
    private static final int OPCODE_CLOSE = 0x8;
    private static final int OPCODE_PING = 0x9;
    private static final int OPCODE_PONG = 0xA;

    private final URI uri;
    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 底层 Socket */
    private volatile Socket socket;
    /** 读线程 */
    private volatile Thread readThread;

    // 固定写缓冲区（避免频繁创建）
    private final byte[] writeBuf = new byte[8192];

    public RawWsClient(URI uri, Listener listener) {
        this.uri = uri;
        this.listener = listener;
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * 启动连接（同步阻塞直到连接建立或失败）
     */
    public boolean connect() {
        if (!running.compareAndSet(false, true)) {
            return false;
        }

        try {
            doConnect();
            return true;
        } catch (Exception e) {
            running.set(false);
            LOG.error("[RawWs] Connect failed: {}", e.getMessage(), e);
            listener.onError(e);
            return false;
        }
    }

    /**
     * 断开连接
     */
    public void disconnect() {
        running.set(false);

        if (readThread != null) {
            readThread.interrupt();
            readThread = null;
        }

        closeSocket(1000, "client close");
    }

    /**
     * 发送二进制数据（作为 WebSocket 二进制帧发送，不压缩）
     */
    public synchronized void send(byte[] data) {
        if (!running.get() || socket == null || !socket.isConnected()) {
            LOG.warn("[RawWs] Cannot send: not connected");
            return;
        }

        try {
            OutputStream out = socket.getOutputStream();
            writeFrame(out, OPCODE_BINARY, data, true);
            out.flush();
        } catch (Exception e) {
            LOG.error("[RawWs] Send error: {}", e.getMessage(), e);
            listener.onError(e);
        }
    }

    // ==================== 连接建立 ====================

    private void doConnect() throws Exception {
        int port = uri.getPort();
        if (port == -1) port = uri.getScheme().equals("wss") ? 443 : 80;
        boolean isSSL = uri.getScheme().equalsIgnoreCase("wss");

        // 建立 TCP 连接
        InetSocketAddress addr = new InetSocketAddress(uri.getHost(), port);
        if (isSSL) {
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket sslSocket = (SSLSocket) factory.createSocket();
            sslSocket.connect(addr, 30000);
            sslSocket.startHandshake();
            socket = sslSocket;
        } else {
            socket = SocketFactory.getDefault().createSocket();
            socket.connect(addr, 30000);
        }
        socket.setSoTimeout(60000); // 60秒读超时
        socket.setTcpNoDelay(true);

        // HTTP 升级握手
        doHandshake();

        listener.onOpen();

        // 启动读线程
        readThread = new Thread(this::readLoop, "raw-ws-read");
        readThread.setDaemon(true);
        readThread.start();
    }

    // ==================== HTTP 升级握手 ====================

    private void doHandshake() throws Exception {
        byte[] keyBytes = new byte[16];
        new SecureRandom().nextBytes(keyBytes);
        String secKey = Base64.getEncoder().encodeToString(keyBytes);

        StringBuilder req = new StringBuilder();
        req.append("GET ").append(uri.getPath()).append("?").append(uri.getQuery() == null ? "" : uri.getQuery()).append(" HTTP/1.1\r\n");
        req.append("Host: ").append(uri.getHost()).append("\r\n");
        req.append("Upgrade: websocket\r\n");
        req.append("Connection: Upgrade\r\n");
        req.append("Sec-WebSocket-Key: ").append(secKey).append("\r\n");
        req.append("Sec-WebSocket-Version: 13\r\n");
        req.append("\r\n");

        byte[] reqBytes = req.toString().getBytes(StandardCharsets.UTF_8);
        OutputStream out = socket.getOutputStream();
        out.write(reqBytes);
        out.flush();

        // 读取响应头
        InputStream in = socket.getInputStream();
        ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
        int lastFour = 0;
        while (true) {
            int b = in.read();
            if (b == -1) throw new IOException("Connection closed during handshake");
            headerBuf.write(b);
            lastFour = (lastFour << 8) | (b & 0xFF);
            if (lastFour == 0x0D0A0D0A) break; // \r\n\r\n（HTTP 头结束标记）
        }

        String response = headerBuf.toString("UTF-8");
        if (LOG.isDebugEnabled()) {
            LOG.debug("[RawWs] Handshake response:\n{}", response);
        }

        // 校验状态码
        if (!response.contains(" 101 ")) {
            throw new IOException("Handshake failed: " + response.substring(0, Math.min(response.length(), 100)));
        }
    }

    // ==================== WebSocket 帧读取循环 ====================

    private void readLoop() {
        try {
            InputStream in = socket.getInputStream();
            byte[] headerBuf = new byte[14]; // 最大帧头长度

            while (running.get() && !Thread.currentThread().isInterrupted()) {
                // 读取前 2 字节（基础帧头）
                readFully(in, headerBuf, 0, 2);
                int b0 = headerBuf[0] & 0xFF;
                int b1 = headerBuf[1] & 0xFF;

                int opcode = b0 & 0x0F;
                boolean masked = (b1 & 0x80) != 0;
                long payloadLen = b1 & 0x7F;

                // 扩展长度
                int headerLen = 2;
                if (payloadLen == 126) {
                    readFully(in, headerBuf, 2, 2);
                    payloadLen = ((headerBuf[2] & 0xFF) << 8) | (headerBuf[3] & 0xFF);
                    headerLen = 4;
                } else if (payloadLen == 127) {
                    readFully(in, headerBuf, 2, 8);
                    payloadLen = 0;
                    for (int i = 0; i < 8; i++) {
                        payloadLen = (payloadLen << 8) | (headerBuf[2 + i] & 0xFF);
                    }
                    headerLen = 10;
                }

                // Masking key
                int maskStart = headerLen;
                if (masked) {
                    readFully(in, headerBuf, headerLen, 4);
                    headerLen += 4;
                }

                // 读取 payload
                byte[] payload;
                if (payloadLen > Integer.MAX_VALUE - 1024) {
                    throw new IOException("Frame too large: " + payloadLen);
                }
                int intLen = (int) payloadLen;
                payload = new byte[intLen];
                readFully(in, payload, 0, intLen);

                // 解掩码
                if (masked) {
                    for (int i = 0; i < intLen; i++) {
                        payload[i] = (byte) (payload[i] ^ headerBuf[maskStart + (i % 4)]);
                    }
                }

                byte[] data = payload;

                // 处理帧
                switch (opcode) {
                    case OPCODE_BINARY:
                        listener.onBinary(ByteBuffer.wrap(data));
                        break;
                    case OPCODE_TEXT:
                        // 飞书不使用文本帧
                        break;
                    case OPCODE_PING:
                        sendPong(data);
                        break;
                    case OPCODE_PONG:
                        // 收到服务端 pong，无需处理
                        break;
                    case OPCODE_CLOSE:
                        int closeCode = 1005;
                        String reason = "";
                        if (data.length >= 2) {
                            closeCode = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
                            if (data.length > 2) {
                                reason = new String(data, 2, data.length - 2, StandardCharsets.UTF_8);
                            }
                        }
                        running.set(false);
                        listener.onClose(closeCode, reason);
                        return;
                    case OPCODE_CONTINUATION:
                        // 飞书不使用分段帧
                        break;
                }
            }
        } catch (Exception e) {
            if (running.get()) {
                LOG.error("[RawWs] Read error: {}", e.getMessage(), e);
                listener.onError(e);
            }
        } finally {
            running.set(false);
            closeSocket(1006, "read error");
        }
    }

    // ==================== 帧写入 ====================

    /**
     * 写入一个 WebSocket 帧
     *
     * @param out     输出流
     * @param opcode  操作码
     * @param data    负载数据
     * @param masked  是否掩码（客户端发送必须 masked）
     */
    private void writeFrame(OutputStream out, int opcode, byte[] data, boolean masked) throws IOException {
        byte[] frameData = data;

        int len = frameData.length;
        int headerSize = 2 + (masked ? 4 : 0);
        if (len >= 126 && len <= 65535) headerSize += 2;
        else if (len > 65535) headerSize += 8;

        byte[] header = new byte[headerSize];
        int pos = 0;

        // Byte 0: FIN + RSV + opcode
        header[pos] = (byte) (0x80 | opcode); // FIN=1
        pos++;

        // Byte 1: MASK + payload length
        if (masked) header[pos] = (byte) 0x80;
        if (len < 126) {
            header[pos] |= (byte) len;
            pos++;
        } else if (len <= 65535) {
            header[pos] |= 126;
            pos++;
            header[pos++] = (byte) ((len >> 8) & 0xFF);
            header[pos++] = (byte) (len & 0xFF);
        } else {
            header[pos] |= 127;
            pos++;
            for (int i = 7; i >= 0; i--) {
                header[pos++] = (byte) ((len >> (i * 8)) & 0xFF);
            }
        }

        // Masking key
        byte[] mask = null;
        if (masked) {
            mask = new byte[4];
            new SecureRandom().nextBytes(mask);
            System.arraycopy(mask, 0, header, pos, 4);
            pos += 4;
        }

        // 写帧头
        out.write(header, 0, headerSize);

        // 写负载（带掩码）
        if (masked) {
            for (int i = 0; i < len; i++) {
                out.write(frameData[i] ^ mask[i % 4]);
            }
        } else {
            out.write(frameData);
        }
    }

    /**
     * 发送 Pong 帧
     */
    private void sendPong(byte[] data) {
        try {
            OutputStream out = socket.getOutputStream();
            // Pong 帧一般不需要压缩
            int len = data.length;
            byte[] header;
            if (len < 126) {
                header = new byte[6]; // 2 header + 4 mask
                header[1] = (byte) (0x80 | len);
            } else if (len <= 65535) {
                header = new byte[8];
                header[1] = (byte) (0x80 | 126);
                header[2] = (byte) ((len >> 8) & 0xFF);
                header[3] = (byte) (len & 0xFF);
            } else {
                header = new byte[14];
                header[1] = (byte) (0x80 | 127);
                for (int i = 7; i >= 0; i--) {
                    header[2 + (7 - i)] = (byte) ((len >> (i * 8)) & 0xFF);
                }
            }

            header[0] = (byte) (0x80 | OPCODE_PONG); // FIN=1, opcode=PONG

            // Masking key
            byte[] mask = new byte[4];
            new SecureRandom().nextBytes(mask);
            System.arraycopy(mask, 0, header, header.length - 4, 4);

            out.write(header);
            for (int i = 0; i < len; i++) {
                out.write(data[i] ^ mask[i % 4]);
            }
            out.flush();
        } catch (Exception e) {
            LOG.warn("[RawWs] sendPong error: {}", e.getMessage());
        }
    }

    // ==================== 工具 ====================

    private void readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        while (len > 0) {
            int n = in.read(buf, off, len);
            if (n == -1) throw new IOException("Connection closed");
            off += n;
            len -= n;
        }
    }

    private void closeSocket(int code, String reason) {
        // 发送关闭帧
        if (socket != null && socket.isConnected()) {
            try {
                byte[] closePayload = new byte[2];
                closePayload[0] = (byte) ((code >> 8) & 0xFF);
                closePayload[1] = (byte) (code & 0xFF);

                OutputStream out = socket.getOutputStream();
                writeFrame(out, OPCODE_CLOSE, closePayload, true);
                out.flush();
            } catch (Exception ignored) {
            }

            try {
                socket.close();
            } catch (Exception ignored) {
            }
            socket = null;
        }

        listener.onClose(code, reason);
    }
}
