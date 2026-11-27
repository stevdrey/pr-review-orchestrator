package io.github.stevdrey.prrevieworchestrator.config;

import io.github.stevdrey.prrevieworchestrator.application.*;
import io.github.stevdrey.prrevieworchestrator.ports.CodexCompletionSource;
import io.github.stevdrey.prrevieworchestrator.ports.FixCorrelationStore;
import io.github.stevdrey.prrevieworchestrator.ports.ReviewEvidenceStore;
import io.github.stevdrey.prrevieworchestrator.ports.GeneralFeedbackStore;
import io.github.stevdrey.prrevieworchestrator.ports.ReviewOutcomeStore;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ReviewEvidenceConfiguration {
    @Bean
    CorrelateFixes correlateFixes(RepositoryAuthorization authorization, IngestReviewEvidence evidence,
            FixCorrelationStore store) {
        return new CorrelateFixes(authorization, evidence, store);
    }

    @Bean
    AssessReviewOutcome assessReviewOutcome(ReviewOutcomeStore store, RepositoryAuthorization authorization,
            Optional<CodexCompletionSource> codex) {
        return new AssessReviewOutcome(store, authorization, codex);
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
