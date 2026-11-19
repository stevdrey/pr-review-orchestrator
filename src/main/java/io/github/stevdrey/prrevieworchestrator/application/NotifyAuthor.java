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

    /** Returns true only when a claimed job was skipped locally without contacting GitHub. */
    public boolean processNext() {
        var claimed = store.claim();
        if (claimed.isEmpty()) return false;
        var job = claimed.get();
        Runnable authorize = () -> authorization.requireAuthorized(job.pr().scope());
        try {
            authorize.run();
            // Superseded intents are common; settle them before minting a token or calling GitHub.
            if (!store.isCurrent(job, authorize)) {
                store.skip(job);
                return true;
            }
            if (!publisher.publish(job, (recipient, post) -> store.deliver(job, recipient, authorize, post))) {
                store.skip(job);
            }
        } catch (AuthorizationDenied denied) {
            store.skip(job);
        }
        return false;
    }
}
