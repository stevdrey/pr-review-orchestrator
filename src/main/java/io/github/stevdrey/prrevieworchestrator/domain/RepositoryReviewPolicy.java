package io.github.stevdrey.prrevieworchestrator.domain;

import io.github.stevdrey.prrevieworchestrator.domain.ReviewOutcome.Stage;
import java.time.Instant;
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
        return iterationCap > 0 && providers.contains(provider)
                && stages.stream().anyMatch(allowed -> allowed.name().equals(stage))
                && (quietHours == null || !quietHours.contains(now));
    }

    /** A daily wall-clock interval in an IANA time zone, start inclusive and end exclusive. */
    public record QuietHours(ZoneId zone, int startMinute, int endMinute) {
        public QuietHours {
            Objects.requireNonNull(zone);
            if (!ZoneId.getAvailableZoneIds().contains(zone.getId()) || startMinute < 0 || startMinute >= 1440
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
    }

    public record Snapshot(long version, RepositoryReviewPolicy policy, boolean held) {
        public Snapshot {
            Objects.requireNonNull(policy);
            if (version < 0) throw new IllegalArgumentException("Invalid repository policy version");
        }

        public boolean permits(String stage, String provider, Instant now) {
            return !held && policy.permits(stage, provider, now);
        }

        public int effectiveIterationCap(Snapshot active) {
            return Math.min(policy.iterationCap(), active.policy().iterationCap());
        }
    }
}
