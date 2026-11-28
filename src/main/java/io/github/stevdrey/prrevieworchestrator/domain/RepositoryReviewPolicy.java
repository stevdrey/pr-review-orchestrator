package io.github.stevdrey.prrevieworchestrator.domain;

import io.github.stevdrey.prrevieworchestrator.domain.ReviewOutcome.Stage;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Set;

/** Local limits on review actions. Authorization, provider capability and budgets are independent gates. */
public record RepositoryReviewPolicy(Set<Stage> stages, Set<String> providers, boolean requirePassingCi,
        QuietHours quietHours, int iterationCap) {
    public static final int MAX_ITERATIONS = 6;
    public static final RepositoryReviewPolicy DEFAULT = new RepositoryReviewPolicy(Set.of(), Set.of(), true, null, 0);

    public RepositoryReviewPolicy {
        stages = Set.copyOf(stages);
        providers = Set.copyOf(providers);
        if (iterationCap < 0 || iterationCap > MAX_ITERATIONS || providers.size() > 16
                || providers.stream().anyMatch(provider -> !provider.matches("[a-z0-9_-]{1,32}"))) {
            throw new IllegalArgumentException("Invalid repository review policy");
        }
    }

    public boolean permits(String stage, String provider, Instant now) {
        return allows(stage, provider) && !quiet(now);
    }

    /** The stage, provider and cap rules, independent of the quiet-hours schedule. */
    public boolean allows(String stage, String provider) {
        return iterationCap > 0 && providers.contains(provider)
                && stages.stream().anyMatch(allowed -> allowed.name().equals(stage));
    }

    public boolean quiet(Instant now) {
        return quietHours != null && quietHours.contains(now);
    }

    /** A daily wall-clock interval in an IANA time zone, start inclusive and end exclusive. */
    public record QuietHours(ZoneId zone, int startMinute, int endMinute) {
        private static final Set<String> ZONES = ZoneId.getAvailableZoneIds();

        public QuietHours {
            Objects.requireNonNull(zone);
            if (!ZONES.contains(zone.getId()) || startMinute < 0 || startMinute >= 1440
                    || endMinute < 0 || endMinute >= 1440 || startMinute == endMinute) {
                throw new IllegalArgumentException("Invalid repository quiet hours");
            }
        }

        public boolean contains(Instant now) {
            var local = now.atZone(zone);
            int minute = local.getHour() * 60 + local.getMinute();
            return startMinute < endMinute ? minute >= startMinute && minute < endMinute
                    : minute >= startMinute || minute < endMinute;
        }

        /** The first instant at or after {@code now} outside the quiet interval. */
        public Instant end(Instant now) {
            var endTime = LocalTime.of(endMinute / 60, endMinute % 60);
            var rules = zone.getRules();
            var cursor = now;
            while (contains(cursor)) {
                var local = cursor.atZone(zone);
                var localEnd = local.toLocalDate().atTime(endTime);
                if (!localEnd.isAfter(local.toLocalDateTime())) localEnd = localEnd.plusDays(1);
                var endWithCurrentOffset = localEnd.toInstant(local.getOffset());
                var transition = rules.nextTransition(cursor);
                if (transition == null || endWithCurrentOffset.isBefore(transition.getInstant())) {
                    return endWithCurrentOffset;
                }
                // At a gap or overlap, recheck the new wall-clock time before choosing a release.
                cursor = transition.getInstant();
            }
            return cursor;
        }
    }

    public record Snapshot(long version, RepositoryReviewPolicy policy, boolean held) {
        public Snapshot {
            Objects.requireNonNull(policy);
            if (version < 0) throw new IllegalArgumentException("Invalid repository policy version");
        }

        public boolean permits(String stage, String provider, Instant now) {
            return !held && policy.permits(stage, provider, now);
        }

        public boolean quiet(Instant now) {
            return policy.quiet(now);
        }

        public int effectiveIterationCap(Snapshot active) {
            return Math.min(policy.iterationCap(), active.policy().iterationCap());
        }
    }
}
