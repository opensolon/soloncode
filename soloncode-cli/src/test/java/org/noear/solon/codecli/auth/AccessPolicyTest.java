package org.noear.solon.codecli.auth;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AccessPolicyTest {
    @Test
    public void localPolicyOnlyAllowsLoopback() {
        assertTrue(AccessPolicy.isAllowed(AccessPolicy.LOCAL, Collections.<String>emptyList(), "127.0.0.1", true));
        assertTrue(AccessPolicy.isAllowed(AccessPolicy.LOCAL, Collections.<String>emptyList(), "::1", true));
        assertFalse(AccessPolicy.isAllowed(AccessPolicy.LOCAL, Collections.<String>emptyList(), "192.168.1.10", true));
    }

    @Test
    public void allowlistMatchesCanonicalIpv4AndIpv6Addresses() {
        assertTrue(AccessPolicy.isAllowed(AccessPolicy.ALLOWLIST,
                Arrays.asList("192.168.1.10", "0:0:0:0:0:0:0:1"), "192.168.1.10", true));
        assertTrue(AccessPolicy.isAllowed(AccessPolicy.ALLOWLIST,
                Collections.singletonList("::1"), "0:0:0:0:0:0:0:1", true));
        assertFalse(AccessPolicy.isAllowed(AccessPolicy.ALLOWLIST,
                Collections.singletonList("192.168.1.10"), "192.168.1.11", true));
    }

    @Test
    public void anyModeAllowsAnySourceAndLegacyAuthenticatedModeIsMigrated() {
        assertTrue(AccessPolicy.isAllowed(AccessPolicy.ANY,
                Collections.<String>emptyList(), "203.0.113.10", false));
        assertTrue(AccessPolicy.isAllowed(AccessPolicy.AUTHENTICATED,
                Collections.<String>emptyList(), "203.0.113.10", false));
    }

    @Test
    public void adminAnyModeIsNoLongerAccepted() {
        assertFalse(AccessPolicy.isAllowed(AccessPolicy.ANY,
                Collections.<String>emptyList(), "203.0.113.10", true));
        assertTrue(AccessPolicy.isAllowed(AccessPolicy.ANY,
                Collections.<String>emptyList(), "127.0.0.1", true));
    }

    @Test
    public void hostNamesAndEmptyAllowlistsAreNotAcceptedAsIpRules() {
        assertThrows(IllegalArgumentException.class, () -> AccessPolicy.normalizeIp("localhost"));
        assertFalse(AccessPolicy.isAllowed(AccessPolicy.ALLOWLIST,
                Collections.<String>emptyList(), "127.0.0.1", true));
    }
}
