package org.noear.solon.codecli.channel;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;

class ChannelReplyRouteTest {
    @Test
    void routeAwareReplyMustBeImplementedByEachChannel() throws Exception {
        Method method = Channel.class.getMethod("sendReply", String.class, String.class,
                boolean.class, String.class, String.class, String.class);
        assertFalse(method.isDefault());
        assertTrue(Modifier.isAbstract(method.getModifiers()));
    }
}
