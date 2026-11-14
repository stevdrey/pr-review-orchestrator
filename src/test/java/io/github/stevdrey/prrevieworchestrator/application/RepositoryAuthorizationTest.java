package io.github.stevdrey.prrevieworchestrator.application;

import static org.junit.jupiter.api.Assertions.*;

import io.github.stevdrey.prrevieworchestrator.domain.RepositoryScope;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class RepositoryAuthorizationTest {
    private final RepositoryScope scope = new RepositoryScope(101, 1001);

    @Test
    void requiresExplicitPairAndLiveMembershipOnEveryRequest() {
        var enabled = new AtomicBoolean(true);
        var authorization = new RepositoryAuthorization(Set.of(scope), ignored -> enabled.get());
        assertDoesNotThrow(() -> authorization.requireAuthorized(scope));
        enabled.set(false);
        assertThrows(SecurityException.class, () -> authorization.requireAuthorized(scope));
        enabled.set(true);
        assertDoesNotThrow(() -> authorization.requireAuthorized(scope));
    }

    @Test
    void rejectsCrossedInstallationRepositoryPairsBeforeConsultingDatabase() {
        var authorization = new RepositoryAuthorization(Set.of(scope, new RepositoryScope(202, 2002)), ignored -> {
            fail("Out-of-scope requests must not reach the membership authority");
            return true;
        });
        for (var denied : Set.of(new RepositoryScope(202, 1001), new RepositoryScope(101, 2002),
                new RepositoryScope(303, 1001), new RepositoryScope(101, 3003))) {
            assertThrows(SecurityException.class, () -> authorization.requireAuthorized(denied));
        }
        assertThrows(SecurityException.class, () -> authorization.requireAuthorized(null));
    }

    @Test
    void emptyConfigurationAndUnavailableAuthorityDenyWithoutLeakingCause() {
        var empty = new RepositoryAuthorization(Set.of(), ignored -> true);
        assertThrows(SecurityException.class, () -> empty.requireAuthorized(scope));
        var unavailable = new RepositoryAuthorization(Set.of(scope), ignored -> {
            throw new IllegalStateException("fixture-secret-database-diagnostics");
        });
        var failure = assertThrows(SecurityException.class, () -> unavailable.requireAuthorized(scope));
        assertNull(failure.getCause());
        assertEquals("Repository authorization denied", failure.getMessage());
    }

    @Test
    void stableIdentifiersMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new RepositoryScope(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new RepositoryScope(1, -1));
    }
}
