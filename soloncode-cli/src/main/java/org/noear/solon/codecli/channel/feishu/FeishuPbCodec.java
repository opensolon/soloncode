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
package org.noear.solon.codecli.channel.feishu;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 飞书 WebSocket Protobuf (Pbbp2) 轻量级编解码器
 *
 * <p>无需引入 protobuf-java 库，手写编解码。</p>
 *
 * <p>帧结构（Pbbp2.Frame）：</p>
 * <pre>
 * field 1: seqID      (varint)
 * field 2: logID      (varint)
 * field 3: service    (varint)
 * field 4: method     (varint)  // 0=CONTROL, 1=DATA
 * field 5: headers    (repeated length-delimited)
 * field 6: payloadEncoding (string)
 * field 7: payloadType     (string)
 * field 8: payload    (bytes)
 * </pre>
 *
 * <p>Header 结构：</p>
 * <pre>
 * field 1: key   (string)
 * field 2: value (string)
 * </pre>
 *
 * @author noear 2026/5/10 created
 */
public class FeishuPbCodec {

    // ==================== Frame 结构体 ====================

    public static class Frame {
        public long seqId;
        public long logId;
        public int service;
        public int method;          // 0=CONTROL, 1=DATA
        public List<Header> headers = new ArrayList<>();
        public String payloadEncoding;
        public String payloadType;
        public byte[] payload;

        /**
         * 根据 key 查找 header value
         */
        public String getHeader(String key) {
            for (Header h : headers) {
                if (key.equals(h.key)) return h.value;
            }
            return null;
        }

        /**
         * 获取 payload 的字符串形式
         */
        public String getPayloadAsString() {
            if (payload == null) return null;
            return new String(payload, StandardCharsets.UTF_8);
        }
    }

    public static class Header {
        public String key;
        public String value;

        public Header() {}

        public Header(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }

    // ==================== Protobuf 编码 ====================

    /**
     * 将 Frame 编码为 Protobuf 字节
     *
     * <p>注意：SeqID 和 LogID 在 proto 中声明为 required，即使为 0 也必须写出（SDK 的
     * newPingFrame 调用了 setSeqID(0)/setLogID(0)，protoc 生成的编码器会写出 0）。</p>
     */
    public static byte[] encode(Frame frame) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        // field 1: seqId (varint) — 始终写出，proto 中为 required
        writeTag(baos, 1, WIRE_TYPE_VARINT);
        writeVarint(baos, frame.seqId);

        // field 2: logId (varint) — 始终写出，proto 中为 required
        writeTag(baos, 2, WIRE_TYPE_VARINT);
        writeVarint(baos, frame.logId);

        // field 3: service (varint)
        if (frame.service != 0) {
            writeTag(baos, 3, WIRE_TYPE_VARINT);
            writeVarint(baos, frame.service);
        }

        // field 4: method (varint)
        writeTag(baos, 4, WIRE_TYPE_VARINT);
        writeVarint(baos, frame.method);

        // field 5: headers (repeated length-delimited)
        for (Header h : frame.headers) {
            byte[] headerBytes = encodeHeader(h);
            writeTag(baos, 5, WIRE_TYPE_LENGTH_DELIMITED);
            writeVarint(baos, headerBytes.length);
            baos.write(headerBytes, 0, headerBytes.length);
        }

        // field 6: payloadEncoding (string)
        if (frame.payloadEncoding != null) {
            writeString(baos, 6, frame.payloadEncoding);
        }

        // field 7: payloadType (string)
        if (frame.payloadType != null) {
            writeString(baos, 7, frame.payloadType);
        }

        // field 8: payload (bytes)
        if (frame.payload != null) {
            writeTag(baos, 8, WIRE_TYPE_LENGTH_DELIMITED);
            writeVarint(baos, frame.payload.length);
            baos.write(frame.payload, 0, frame.payload.length);
        }

        return baos.toByteArray();
    }

    private static byte[] encodeHeader(Header h) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        if (h.key != null) {
            writeString(baos, 1, h.key);
        }
        if (h.value != null) {
            writeString(baos, 2, h.value);
        }
        return baos.toByteArray();
    }

    private static void writeString(ByteArrayOutputStream baos, int fieldNum, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeTag(baos, fieldNum, WIRE_TYPE_LENGTH_DELIMITED);
        writeVarint(baos, bytes.length);
        baos.write(bytes, 0, bytes.length);
    }

    // ==================== Protobuf 解码 ====================

    /**
     * 从 ByteBuffer 解析 Frame
     */
    public static Frame decode(ByteBuffer buf) {
        byte[] bytes;
        if (buf.hasArray()) {
            bytes = buf.array();
            int offset = buf.arrayOffset() + buf.position();
            int length = buf.remaining();
            return decode(bytes, offset, length);
        } else {
            bytes = new byte[buf.remaining()];
            buf.get(bytes);
            return decode(bytes, 0, bytes.length);
        }
    }

    /**
     * 从字节数组解析 Frame
     */
    public static Frame decode(byte[] data) {
        return decode(data, 0, data.length);
    }

    public static Frame decode(byte[] data, int offset, int length) {
        Frame frame = new Frame();
        int end = offset + length;
        int pos = offset;

        while (pos < end) {
            long tagAndType = readVarint(data, pos);
            pos += varintSize(tagAndType);
            int fieldNumber = (int) (tagAndType >>> 3);
            int wireType = (int) (tagAndType & 0x7);

            switch (fieldNumber) {
                case 1: // seqId (varint)
                    frame.seqId = readVarint(data, pos);
                    pos += varintSize(frame.seqId);
                    break;
                case 2: // logId (varint)
                    frame.logId = readVarint(data, pos);
                    pos += varintSize(frame.logId);
                    break;
                case 3: // service (varint)
                    frame.service = (int) readVarint(data, pos);
                    pos += varintSize(frame.service);
                    break;
                case 4: // method (varint)
                    frame.method = (int) readVarint(data, pos);
                    pos += varintSize(frame.method);
                    break;
                case 5: { // headers (length-delimited)
                    int headerLen = (int) readVarint(data, pos);
                    pos += varintSize(headerLen);
                    frame.headers.add(decodeHeader(data, pos, headerLen));
                    pos += headerLen;
                    break;
                }
                case 6: { // payloadEncoding (string/length-delimited)
                    int strLen = (int) readVarint(data, pos);
                    pos += varintSize(strLen);
                    frame.payloadEncoding = new String(data, pos, strLen, StandardCharsets.UTF_8);
                    pos += strLen;
                    break;
                }
                case 7: { // payloadType (string/length-delimited)
                    int strLen = (int) readVarint(data, pos);
                    pos += varintSize(strLen);
                    frame.payloadType = new String(data, pos, strLen, StandardCharsets.UTF_8);
                    pos += strLen;
                    break;
                }
                case 8: { // payload (bytes/length-delimited)
                    int payloadLen = (int) readVarint(data, pos);
                    pos += varintSize(payloadLen);
                    frame.payload = new byte[payloadLen];
                    System.arraycopy(data, pos, frame.payload, 0, payloadLen);
                    pos += payloadLen;
                    break;
                }
                default: {
                    // 跳过未知字段
                    pos = skipField(data, pos, wireType);
                    break;
                }
            }
        }

        return frame;
    }

    private static Header decodeHeader(byte[] data, int offset, int length) {
        Header header = new Header();
        int end = offset + length;
        int pos = offset;

        while (pos < end) {
            long tagAndType = readVarint(data, pos);
            pos += varintSize(tagAndType);
            int fieldNumber = (int) (tagAndType >>> 3);
            int wireType = (int) (tagAndType & 0x7);

            switch (fieldNumber) {
                case 1: { // key (string)
                    int strLen = (int) readVarint(data, pos);
                    pos += varintSize(strLen);
                    header.key = new String(data, pos, strLen, StandardCharsets.UTF_8);
                    pos += strLen;
                    break;
                }
                case 2: { // value (string)
                    int strLen = (int) readVarint(data, pos);
                    pos += varintSize(strLen);
                    header.value = new String(data, pos, strLen, StandardCharsets.UTF_8);
                    pos += strLen;
                    break;
                }
                default: {
                    pos = skipField(data, pos, wireType);
                    break;
                }
            }
        }

        return header;
    }

    // ==================== Protobuf 底层工具 ====================

    private static final int WIRE_TYPE_VARINT = 0;
    private static final int WIRE_TYPE_LENGTH_DELIMITED = 2;

    private static void writeTag(ByteArrayOutputStream baos, int fieldNumber, int wireType) {
        writeVarint(baos, ((long) fieldNumber << 3) | wireType);
    }

    private static void writeVarint(ByteArrayOutputStream baos, long value) {
        while (true) {
            if ((value & ~0x7FL) == 0) {
                baos.write((int) value);
                return;
            } else {
                baos.write((int) ((value & 0x7F) | 0x80));
                value >>>= 7;
            }
        }
    }

    private static long readVarint(byte[] data, int offset) {
        long result = 0;
        int shift = 0;
        int pos = offset;
        while (true) {
            if (pos >= data.length) {
                throw new RuntimeException("Protobuf varint overflow: unexpected end of data");
            }
            byte b = data[pos++];
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
            if (shift >= 64) {
                throw new RuntimeException("Protobuf varint too long: exceeds 64 bits");
            }
        }
        return result;
    }

    private static int varintSize(long value) {
        if (value < 0) return 10; // 负数占 10 字节
        int size = 1;
        while ((value & ~0x7FL) != 0) {
            size++;
            value >>>= 7;
        }
        return size;
    }

    private static int skipField(byte[] data, int pos, int wireType) {
        switch (wireType) {
            case 0: { // varint
                while (pos < data.length) {
                    if ((data[pos++] & 0x80) == 0) {
                        return pos;
                    }
                }
                throw new RuntimeException("Protobuf varint field unterminated");
            }
            case 1: { // 64-bit fixed
                return pos + 8;
            }
            case 2: { // length-delimited
                int len = (int) readVarint(data, pos);
                pos += varintSize(len);
                return pos + len;
            }
            case 5: { // 32-bit fixed
                return pos + 4;
            }
            default:
                throw new RuntimeException("Unknown wire type: " + wireType);
        }
    }

    // ==================== 便捷方法 ====================

    /**
     * 构建 DATA 响应帧（与 SDK 一致：method=1 DATA，payload={"code":200,...}）
     *
     * <p>SDK 源码：FrameType 只有 CONTROL(0) 和 DATA(1)，没有 ACK(2)。
     * SDK 用 frame.toBuilder().setPayload(resp).addHeader("biz_rt",...).build() 发回 DATA 帧。</p>
     *
     * <p>关键：SDK 的 toBuilder() 会保留原始帧的 payloadEncoding、payloadType 等所有字段。
     * 我们的 buildDataResponse 也需要将原始帧的 payloadEncoding/payloadType 保留下来，
     * 不要强行覆盖为 "json"（服务器可能不识别）。</p>
     */
    public static byte[] buildDataResponse(Frame originalFrame, byte[] respPayload, long elapsedMs) {
        Frame resp = new Frame();
        resp.seqId = originalFrame.seqId;
        resp.logId = originalFrame.logId;
        resp.service = originalFrame.service;
        resp.method = 1; // DATA（与 SDK 一致）
        resp.headers = new ArrayList<>(originalFrame.headers);
        resp.headers.add(new Header("biz_rt", String.valueOf(elapsedMs)));

        // 保留原始字段（SDK 的 toBuilder() 行为）：不 overwrite
        resp.payloadEncoding = originalFrame.payloadEncoding;
        resp.payloadType = originalFrame.payloadType;
        resp.payload = respPayload;
        return encode(resp);
    }

    /**
     * 构建 Ping 帧（与 SDK 一致）
     *
     * <p>SDK 源码：newPingFrame(serviceId) → service=serviceId, method=0, headers=[type=ping], seqId=0, logId=0</p>
     */
    public static byte[] buildPing(int serviceId) {
        Frame ping = new Frame();
        ping.seqId = 0;
        ping.logId = 0;
        ping.service = serviceId;
        ping.method = 0; // CONTROL
        ping.headers.add(new Header("type", "ping"));
        return encode(ping);
    }
}
