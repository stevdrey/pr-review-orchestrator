package io.github.stevdrey.prrevieworchestrator.config;

import io.github.stevdrey.prrevieworchestrator.application.RepositoryAuthorization;
import io.github.stevdrey.prrevieworchestrator.application.ReviewLifecycle;
import io.github.stevdrey.prrevieworchestrator.ports.ReviewStateStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Exposes internal use cases without enabling any webhook planning or provider calls. */
@Configuration(proxyBeanMethods = false)
public class ReviewLifecycleConfiguration {
    @Bean
    ReviewLifecycle reviewLifecycle(ReviewStateStore store, RepositoryAuthorization authorization) {
        return new ReviewLifecycle(store, authorization);
    }
}
