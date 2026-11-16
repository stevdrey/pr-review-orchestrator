package io.github.stevdrey.prrevieworchestrator.config;

import io.github.stevdrey.prrevieworchestrator.application.*;
import io.github.stevdrey.prrevieworchestrator.ports.ReviewEvidenceStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ReviewEvidenceConfiguration {
    @Bean
    IngestReviewEvidence ingestReviewEvidence(RepositoryAuthorization authorization, ReviewEvidenceStore store) {
        return new IngestReviewEvidence(authorization, store);
    }
}
