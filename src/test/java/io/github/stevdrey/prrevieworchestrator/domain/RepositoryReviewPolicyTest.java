package io.github.stevdrey.prrevieworchestrator.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.stevdrey.prrevieworchestrator.domain.RepositoryReviewPolicy.QuietHours;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RepositoryReviewPolicyTest {
    @ParameterizedTest
    @CsvSource({
            "60,90,2026-11-01T05:10:00Z,2026-11-01T05:30:00Z",
            "60,90,2026-11-01T06:10:00Z,2026-11-01T06:30:00Z",
            "105,150,2026-11-01T05:50:00Z,2026-11-01T06:00:00Z",
            "90,150,2026-03-08T06:40:00Z,2026-03-08T07:00:00Z",
            "90,195,2026-03-08T06:40:00Z,2026-03-08T07:15:00Z",
            "60,90,2026-11-03T06:00:00Z,2026-11-03T06:30:00Z",
            "1320,360,2026-11-04T03:00:00Z,2026-11-04T11:00:00Z"
    })
    void quietHoursEndAtTheFirstInstantOutsideTheWindow(int start, int end, Instant now, Instant release) {
        var hours = new QuietHours(ZoneId.of("America/New_York"), start, end);
        assertTrue(hours.contains(now));
        assertEquals(release, hours.end(now));
        assertTrue(hours.contains(release.minusNanos(1)));
        assertFalse(hours.contains(release));
        assertEquals(release, hours.end(release));
    }
}
