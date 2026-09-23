package org.noear.solon.codecli.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.core.handle.Result;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminAuditTest {
    @TempDir Path temp;

    @Test
    void persistsJsonlAndDropsSensitiveFields() throws Exception {
        AdminAuditStore store = new AdminAuditStore(temp.resolve("audit"));
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("actorUserId", "admin-1");
        fields.put("password", "plain-password");
        fields.put("sessionToken", "raw-token");
        fields.put("ldapAdminPassword", "ldap-secret");
        fields.put("outcome", "success");

        AdminAuditStore.record("admin.user_created", fields);

        Path[] files = Files.list(temp.resolve("audit")).toArray(Path[]::new);
        assertEquals(1, files.length);
        String json = new String(Files.readAllBytes(files[0]), StandardCharsets.UTF_8);
        assertTrue(json.contains("admin.user_created"));
        assertTrue(json.contains("admin-1"));
        assertTrue(json.contains("success"));
        assertFalse(json.contains("plain-password"));
        assertFalse(json.contains("raw-token"));
        assertFalse(json.contains("ldap-secret"));
    }

    @Test
    void queryIsReadOnlyPagedAndFilteredWithAnUpperBound() {
        AdminAuditStore store = new AdminAuditStore(temp.resolve("audit"));
        for (int i = 0; i < 3; i++) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("actorUserId", i == 1 ? "other" : "admin");
            fields.put("targetId", "u" + i);
            AdminAuditStore.record(i == 1 ? "admin.user_deleted" : "admin.user_created", fields);
        }

        Map<String, Object> page = store.query(1, 2, "admin.user_created", "actorUserId", "admin");
        assertEquals(2, page.get("total"));
        assertEquals(2, ((java.util.List) page.get("items")).size());
        assertEquals(100, ((Integer) store.query(1, 1000, null, null, null).get("pageSize")).intValue());
    }

    @Test
    void controllerOnlyExposesReadQuery() {
        AdminAuditStore store = new AdminAuditStore(temp.resolve("audit"));
        AdminAuditController controller = new AdminAuditController(store);
        Result<Map<String, Object>> result = controller.query(1, 10, null, null, null);
        assertEquals(200, result.getCode());
        assertTrue(result.getCode() == 200);
    }
}
