package org.noear.solon.codecli.auth;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BasicAuthAuthenticatorTest {
    @Test
    void authenticatesUtf8BasicCredentialsThroughUserStore() {
        UserEntity expected = user("alice", "user");
        UserStore store = store(expected);
        String header = basic("alice", "1234");

        BasicAuthAuthenticator.AuthResult result =
                BasicAuthAuthenticator.authenticate(header, "127.0.0.1", store);

        assertTrue(result.isPresent());
        assertTrue(result.isSuccess());
        assertSame(expected, result.getUser());
    }

    @Test
    void rejectsMalformedOrInvalidBasicCredentials() {
        UserStore store = store(user("alice", "user"));

        assertFalse(BasicAuthAuthenticator.authenticate("Basic !!!", "127.0.0.2", store).isSuccess());
        assertFalse(BasicAuthAuthenticator.authenticate(basic("alice", "123"), "127.0.0.3", store).isSuccess());
        assertFalse(BasicAuthAuthenticator.authenticate(basic("missing", "1234"), "127.0.0.4", store).isSuccess());
        assertFalse(BasicAuthAuthenticator.authenticate("Bearer token", "127.0.0.5", store).isPresent());
    }

    @Test
    void disabledUserCannotAuthenticate() {
        UserEntity disabled = user("alice", "user");
        disabled.setEnabled(false);
        BasicAuthAuthenticator.AuthResult result =
                BasicAuthAuthenticator.authenticate(basic("alice", "1234"), "127.0.0.6", store(disabled));

        assertTrue(result.isPresent());
        assertFalse(result.isSuccess());
    }

    @Test
    void runPathsAreHandledByRunControllerAndNotGenericSessionFilter() {
        assertTrue(UserAuthFilter.isRunPath("/web/run"));
        assertTrue(UserAuthFilter.isRunPath("/web/run/interrupt"));
        assertFalse(UserAuthFilter.isRunPath("/web/run/other"));
    }

    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static UserEntity user(String username, String role) {
        UserEntity user = new UserEntity(username, username, username);
        user.setRole(role);
        user.setEnabled(true);
        return user;
    }

    private static UserStore store(final UserEntity expected) {
        return new UserStore() {
            @Override public void init(UserAuthConfig config) { }
            @Override public UserEntity authenticate(String username, String password) {
                return expected.getUsername().equals(username) && "1234".equals(password) ? expected : null;
            }
            @Override public UserEntity findByUsername(String username) { return expected; }
            @Override public UserEntity findById(String id) { return expected; }
            @Override public List<UserEntity> listUsers() { return new ArrayList<>(); }
            @Override public UserEntity createUser(UserEntity user) { return user; }
            @Override public UserEntity updateUser(UserEntity user) { return user; }
            @Override public void deleteUser(String id) { }
            @Override public String getType() { return "file"; }
        };
    }
}
