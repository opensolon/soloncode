package org.noear.solon.codecli.portal;

import lombok.Data;

import java.util.List;

@Data
public class WsMessage {

    String input;

    String sessionId;

    String model;

    String agent;

    String cwd;

    List<WsAttachment> attachments;

    @Data
    public static class WsAttachment {
        String type;     // "image" | "file"
        String name;
        String data;     // base64 data (image) or text content (file)
        String mimeType; // e.g. "image/png"
    }
}
