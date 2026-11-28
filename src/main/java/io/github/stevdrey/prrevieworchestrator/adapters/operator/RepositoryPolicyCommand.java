package io.github.stevdrey.prrevieworchestrator.adapters.operator;

import io.github.stevdrey.prrevieworchestrator.adapters.persistence.JdbcInstallationAuthorization;
import io.github.stevdrey.prrevieworchestrator.adapters.persistence.JdbcRepositoryPolicy;
import io.github.stevdrey.prrevieworchestrator.application.RepositoryAuthorization;
import io.github.stevdrey.prrevieworchestrator.application.RepositoryPolicyOperator;
import io.github.stevdrey.prrevieworchestrator.config.RepositoryAuthorizationConfiguration;
import io.github.stevdrey.prrevieworchestrator.domain.AuthorizationDenied;
import io.github.stevdrey.prrevieworchestrator.domain.AuthorizationUnavailable;
import io.github.stevdrey.prrevieworchestrator.domain.RepositoryReviewPolicy;
import io.github.stevdrey.prrevieworchestrator.domain.RepositoryReviewPolicy.QuietHours;
import io.github.stevdrey.prrevieworchestrator.domain.RepositoryScope;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewConflict;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewOutcome.Stage;
import io.github.stevdrey.prrevieworchestrator.ports.RepositoryPolicyStore.Change;
import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Local operator entry point. It starts no HTTP routes, provider integrations or background jobs. */
public final class RepositoryPolicyCommand {
    private static final String PREFIX = "orchestrator.repository-policy.operator.";

    private RepositoryPolicyCommand() {}

    @EnableAutoConfiguration
    @Import({RepositoryAuthorizationConfiguration.class, JdbcInstallationAuthorization.class})
    static class OperatorContext {}

    public static int run(String[] args) {
        Command command;
        try {
            command = parse(List.of(args));
        } catch (IllegalArgumentException | DateTimeException invalid) {
            System.err.println("INVALID_REPOSITORY_POLICY_COMMAND");
            return 2;
        }
        try (var context = new SpringApplicationBuilder(OperatorContext.class).web(WebApplicationType.NONE)
                .registerShutdownHook(false).logStartupInfo(false).properties("spring.flyway.enabled=false").run()) {
            var environment = context.getEnvironment();
            var scope = new RepositoryScope(environment.getRequiredProperty(PREFIX + "installation-id", Long.class),
                    environment.getRequiredProperty(PREFIX + "repository-id", Long.class));
            long actor = environment.getRequiredProperty(PREFIX + "actor-id", Long.class);
            var store = new JdbcRepositoryPolicy(context.getBean(JdbcTemplate.class))
                    .operatorStore(context.getBean(PlatformTransactionManager.class));
            var operator = new RepositoryPolicyOperator(store, context.getBean(RepositoryAuthorization.class));
            var result = command.change() == null ? operator.read(scope, actor)
                    : operator.change(scope, actor, command.expectedVersion(), command.change(), command.policy());
            System.out.println(result);
            return 0;
        } catch (AuthorizationDenied | AuthorizationUnavailable | ReviewConflict rejected) {
            System.err.println(rejected.getMessage());
            return 1;
        } catch (RuntimeException failure) {
            System.err.println("REPOSITORY_POLICY_UNAVAILABLE");
            return 1;
        }
    }

    private record Command(Change change, long expectedVersion, RepositoryReviewPolicy policy) {}

    private static Command parse(List<String> values) {
        if (values.equals(List.of("show"))) return new Command(null, 0, null);
        if (values.size() < 2) throw new IllegalArgumentException();
        long expected = Long.parseLong(values.get(1));
        if (expected < 0 || expected == Long.MAX_VALUE) throw new IllegalArgumentException();
        return switch (values.getFirst()) {
            case "pause", "resume" -> {
                if (values.size() != 2) throw new IllegalArgumentException();
                yield new Command("pause".equals(values.getFirst()) ? Change.PAUSE : Change.RESUME, expected, null);
            }
            case "configure" -> new Command(Change.CONFIGURE, expected, parsePolicy(values));
            default -> throw new IllegalArgumentException();
        };
    }

    private static RepositoryReviewPolicy parsePolicy(List<String> values) {
        if (values.size() != 6 && values.size() != 9) throw new IllegalArgumentException();
        int cap = Integer.parseInt(values.get(2));
        boolean requireCi = switch (values.get(3)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException();
        };
        var stages = entries(values.get(4)).stream().map(Stage::valueOf).collect(Collectors.toUnmodifiableSet());
        var providers = entries(values.get(5));
        var quiet = values.size() == 6 ? null : new QuietHours(ZoneId.of(values.get(6)), minute(values.get(7)),
                minute(values.get(8)));
        return new RepositoryReviewPolicy(stages, providers, requireCi, quiet, cap);
    }

    private static Set<String> entries(String list) {
        return "-".equals(list) ? Set.of() : Set.copyOf(Arrays.asList(list.split(",", -1)));
    }

    private static int minute(String value) {
        if (!value.matches("[0-9]{2}:[0-9]{2}")) throw new IllegalArgumentException();
        var time = LocalTime.parse(value);
        return time.getHour() * 60 + time.getMinute();
    }
}
