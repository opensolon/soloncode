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
package org.noear.solon.ai.codecli.core.message;

/**
 * 消息确认
 *
 * @author bai
 * @since 3.9.5
 */
public class MessageAck {
    private final String messageId;
    private final String receiver;
    private final boolean success;
    private final String message;
    private final long timestamp;

    public MessageAck(String messageId, String receiver, boolean success, String message) {
        this.messageId = messageId;
        this.receiver = receiver;
        this.success = success;
        this.message = message;
        this.timestamp = System.currentTimeMillis();
    }

    public String getMessageId() {
        return messageId;
    }

    public String getReceiver() {
        return receiver;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "MessageAck{" +
                "messageId='" + messageId + '\'' +
                ", receiver='" + receiver + '\'' +
                ", success=" + success +
                ", message='" + message + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}
