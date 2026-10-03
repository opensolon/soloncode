package org.noear.solon.codecli.api.desktop;

import lombok.Data;

import java.util.List;

@Data
public class WsMessage {

    String input;

    String sessionId;

    String model;

    String agent;

    String cwd;

    String mode; // "default" | "auto" | "plan" | "goal"

    String reasoningEffort; // "low" | "medium" | "high" | "max"

    Long goalMaxTokens;

    Long goalMaxDurationMinutes;

    Integer goalMaxIterations;

    String goalObjective;

    List<WsAttachment> attachments;

    @Data
    public static class WsAttachment {
        String type;     // "image" | "file"
        String name;
        String data;     // attachment payload
        String mimeType; // e.g. "image/png"
        String encoding; // "base64" | "text" (legacy)
    }
}
