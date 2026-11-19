package io.github.stevdrey.prrevieworchestrator.application;

import io.github.stevdrey.prrevieworchestrator.domain.AuthorizationDenied;
import io.github.stevdrey.prrevieworchestrator.ports.AuthorNoticePublisher;
import io.github.stevdrey.prrevieworchestrator.ports.AuthorNoticeStore;

public final class NotifyAuthor {
    private final AuthorNoticeStore store;
    private final AuthorNoticePublisher publisher;
    private final RepositoryAuthorization authorization;

    public NotifyAuthor(AuthorNoticeStore store, AuthorNoticePublisher publisher, RepositoryAuthorization authorization) {
        this.store = store;
        this.publisher = publisher;
        this.authorization = authorization;
    }

    public void processNext() {
        store.claim().ifPresent(job -> {
            Runnable authorize = () -> authorization.requireAuthorized(job.pr().scope());
            try {
                authorize.run();
                var receipt = publisher.publish(job, recipient -> store.reserve(job, recipient, authorize));
                if (receipt.isPresent()) store.sent(job, receipt.getAsLong());
                else store.skip(job);
            } catch (AuthorizationDenied denied) {
                store.skip(job);
            }
        });
    }
}
