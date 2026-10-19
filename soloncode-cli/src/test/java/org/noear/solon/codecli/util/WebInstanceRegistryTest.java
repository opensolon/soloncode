package org.noear.solon.codecli.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class WebInstanceRegistryTest {
    @Test
    void generationDoesNotPublishOrReplaceRunningInstance() {
        int port = 56000 + (int) Math.floorMod(System.nanoTime(), 1000L);
        String running = WebInstanceRegistry.register(port);
        try {
            String pending = WebInstanceRegistry.generateToken();
            assertNotEquals(running, pending);
            assertEquals(running, WebInstanceRegistry.readToken(port));
            WebInstanceRegistry.remove(port, pending);
            assertEquals(running, WebInstanceRegistry.readToken(port));
            assertEquals(pending, WebInstanceRegistry.register(port, pending));
            assertEquals(pending, WebInstanceRegistry.readToken(port));
            WebInstanceRegistry.remove(port, running);
            assertEquals(pending, WebInstanceRegistry.readToken(port));
            WebInstanceRegistry.remove(port, pending);
            org.junit.jupiter.api.Assertions.assertNull(WebInstanceRegistry.readToken(port));
        } finally {
            WebInstanceRegistry.remove(port);
        }
    }

    @Test
    void invalidPublicationPreservesExistingRegistration() {
        int port = 57000 + (int) Math.floorMod(System.nanoTime(), 1000L);
        String token = WebInstanceRegistry.register(port);
        try {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> WebInstanceRegistry.register(port, null));
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> WebInstanceRegistry.register(port, "  "));
            assertEquals(token, WebInstanceRegistry.readToken(port));
            WebInstanceRegistry.remove(port, null);
            assertEquals(token, WebInstanceRegistry.readToken(port));
        } finally {
            WebInstanceRegistry.remove(port, token);
        }
    }

    @Test
    void registerReadAndRemove_roundTripsOnlyAttachMetadata() throws Exception {
        int port = 49152 + (int) (System.nanoTime() % 1000);
        String token = WebInstanceRegistry.register(port);
        try {
            assertNotNull(token);
            assertNotEquals("", token);
            assertEquals(token, WebInstanceRegistry.readToken(port));
            String json = new String(Files.readAllBytes(WebInstanceRegistry.fileForPort(port)), "UTF-8");
            org.junit.jupiter.api.Assertions.assertTrue(json.contains("\"product\":\"soloncode\""));
            org.junit.jupiter.api.Assertions.assertFalse(json.contains("password"));
        } finally {
            WebInstanceRegistry.remove(port);
        }
        org.junit.jupiter.api.Assertions.assertNull(WebInstanceRegistry.readToken(port));
    }

    @Test
    void oldShutdownHookCannotRemoveNewInstanceRegistration() {
        int port = 49152 + (int) (System.nanoTime() % 1000);
        String oldToken = WebInstanceRegistry.register(port);
        String newToken = WebInstanceRegistry.register(port);
        try {
            WebInstanceRegistry.remove(port, oldToken);
            assertEquals(newToken, WebInstanceRegistry.readToken(port));
        } finally {
            WebInstanceRegistry.remove(port, newToken);
        }
    }
}
