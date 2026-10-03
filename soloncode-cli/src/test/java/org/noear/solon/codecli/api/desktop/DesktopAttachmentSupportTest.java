package org.noear.solon.codecli.api.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopAttachmentSupportTest {
    @TempDir
    Path workspace;

    @Test
    void decodesAndStoresFileInsideWorkspaceUploads() throws Exception {
        WsMessage.WsAttachment attachment = attachment("file", "notes.txt", "text/plain", "hello");

        byte[] bytes = DesktopAttachmentSupport.decode(attachment);
        String relativePath = DesktopAttachmentSupport.save(workspace, attachment.getName(), bytes);

        assertEquals(".uploads/notes.txt", relativePath);
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(workspace.resolve(relativePath)));
    }

    @Test
    void rejectsTraversalAndInvalidBase64() {
        WsMessage.WsAttachment traversal = attachment("file", "../outside.txt", "text/plain", "hello");
        byte[] bytes = DesktopAttachmentSupport.decode(traversal);
        assertThrows(IllegalArgumentException.class,
                () -> DesktopAttachmentSupport.save(workspace, traversal.getName(), bytes));

        WsMessage.WsAttachment invalid = attachment("file", "safe.txt", "text/plain", "hello");
        invalid.setData("not-base64!!");
        assertThrows(IllegalArgumentException.class, () -> DesktopAttachmentSupport.decode(invalid));
    }

    @Test
    void multimodalImageRequiresSupportedExtensionAndMimeType() {
        WsMessage.WsAttachment png = attachment("image", "chart.png", "image/png", "image");
        assertTrue(DesktopAttachmentSupport.isMultimodalImage(png));

        WsMessage.WsAttachment renamed = attachment("image", "chart.txt", "image/png", "image");
        assertFalse(DesktopAttachmentSupport.isMultimodalImage(renamed));

        WsMessage.WsAttachment wrongMime = attachment("image", "chart.png", "text/html", "image");
        assertFalse(DesktopAttachmentSupport.isMultimodalImage(wrongMime));
    }

    private static WsMessage.WsAttachment attachment(String type, String name, String mimeType, String text) {
        WsMessage.WsAttachment attachment = new WsMessage.WsAttachment();
        attachment.setType(type);
        attachment.setName(name);
        attachment.setMimeType(mimeType);
        attachment.setEncoding("base64");
        attachment.setData(Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)));
        return attachment;
    }
}
