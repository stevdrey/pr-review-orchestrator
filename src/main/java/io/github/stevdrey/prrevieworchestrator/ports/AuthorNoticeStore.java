package io.github.stevdrey.prrevieworchestrator.ports;

import io.github.stevdrey.prrevieworchestrator.domain.AuthorNotice.*;
import java.util.Optional;

public interface AuthorNoticeStore {
    Optional<Job> claim();

    /** Commit an irrevocable send permit only for the current transition and recipient. */
    Optional<Evidence> reserve(Job job, long recipientId, Runnable authorize);

    void skip(Job job);
    void sent(Job job, long commentId);
}
