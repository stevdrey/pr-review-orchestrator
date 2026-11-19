package io.github.stevdrey.prrevieworchestrator.ports;

import io.github.stevdrey.prrevieworchestrator.domain.AuthorNotice.*;
import java.util.Optional;
import java.util.function.ToLongFunction;

public interface AuthorNoticeStore {
    Optional<Job> claim();

    /** Local, authorized check that the job still describes the current state entry; no remote calls. */
    boolean isCurrent(Job job, Runnable authorize);

    /** Commit the one-shot permit, then keep local authorization valid throughout the bounded POST. */
    boolean deliver(Job job, long recipientId, Runnable authorize, ToLongFunction<Evidence> post);

    void skip(Job job);
}
