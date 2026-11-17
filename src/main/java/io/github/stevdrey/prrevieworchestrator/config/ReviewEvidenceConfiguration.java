package io.github.stevdrey.prrevieworchestrator.config;

import io.github.stevdrey.prrevieworchestrator.application.*;
import io.github.stevdrey.prrevieworchestrator.ports.ReviewEvidenceStore;
import io.github.stevdrey.prrevieworchestrator.ports.GeneralFeedbackStore;
import io.github.stevdrey.prrevieworchestrator.ports.ReviewOutcomeStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ReviewEvidenceConfiguration {
    @Bean
    AssessReviewOutcome assessReviewOutcome(ReviewOutcomeStore store, RepositoryAuthorization authorization) {
        return new AssessReviewOutcome(store, authorization);
    }

    @Bean
    DisposeGeneralFeedback disposeGeneralFeedback(RepositoryAuthorization authorization, GeneralFeedbackStore store) {
        return new DisposeGeneralFeedback(authorization, store);
    }

    @Bean
    IngestReviewEvidence ingestReviewEvidence(RepositoryAuthorization authorization, ReviewEvidenceStore store) {
        return new IngestReviewEvidence(authorization, store);
    }
}
