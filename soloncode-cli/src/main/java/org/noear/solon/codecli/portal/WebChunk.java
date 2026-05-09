package org.noear.solon.codecli.portal;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;

/**
 *
 * @author noear 2026/5/8 created
 *
 */
@Getter
@Setter
public class WebChunk {
    public static final WebChunk EMPTY = new WebChunk();

    public static boolean isNotEmpty(WebChunk chunk) {
        return chunk != null && chunk.type != null;
    }


    private String sessionId;
    private String type;
    private String text;
    private String toolName;
    private Map<String, Object> args;
    private String command;
    private Long createdAt;

    public static WebChunk ofDone() {
        WebChunk tmp = new WebChunk();
        tmp.type = "done";
        tmp.createdAt = Instant.now().toEpochMilli();

        return tmp;
    }

    public static WebChunk ofError(String text) {
        WebChunk tmp = new WebChunk();
        tmp.type = "error";
        tmp.text = text;
        tmp.createdAt = Instant.now().toEpochMilli();

        return tmp;
    }

    public static WebChunk ofError(Throwable err) {
        WebChunk tmp = new WebChunk();
        tmp.type = "error";
        tmp.text = (err.getMessage() == null ? "Unknown error" : err.getMessage());
        tmp.createdAt = Instant.now().toEpochMilli();

        return tmp;
    }

    public static WebChunk ofText(String text) {
        WebChunk tmp = new WebChunk();
        tmp.type = "text";
        tmp.text = text;
        tmp.createdAt = Instant.now().toEpochMilli();

        return tmp;
    }

    public static WebChunk ofReason(String text) {
        WebChunk tmp = new WebChunk();
        tmp.type = "reason";
        tmp.text = text;
        tmp.createdAt = Instant.now().toEpochMilli();

        return tmp;
    }


    public static WebChunk ofAction(String text) {
        WebChunk tmp = new WebChunk();
        tmp.type = "action";
        tmp.text = text;
        tmp.createdAt = Instant.now().toEpochMilli();

        return tmp;
    }

    public static WebChunk ofCommand(String text){
        WebChunk tmp = new WebChunk();
        tmp.type = "command";
        tmp.text = text;
        tmp.createdAt = Instant.now().toEpochMilli();

        return tmp;
    }

    public static WebChunk ofRewind(int count) {
        WebChunk tmp = new WebChunk();
        tmp.type = "rewind";
        tmp.text = String.valueOf(count);
        tmp.createdAt = Instant.now().toEpochMilli();

        return tmp;
    }

    public static WebChunk ofHitl(String toolName, String command) {
        WebChunk tmp = new WebChunk();
        tmp.type = "hitl";
        tmp.toolName = toolName;
        tmp.command = command;
        tmp.createdAt = Instant.now().toEpochMilli();

        return tmp;
    }
}