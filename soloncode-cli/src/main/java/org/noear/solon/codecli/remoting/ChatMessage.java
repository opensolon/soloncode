package org.noear.solon.codecli.remoting;

import lombok.Data;

@Data
public class ChatMessage {

    String input;

    String sessionId;

    String model;

    String agent;

    String sessionCwd;
}
