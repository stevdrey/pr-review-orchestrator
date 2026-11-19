package io.github.stevdrey.prrevieworchestrator.ports;

import io.github.stevdrey.prrevieworchestrator.domain.AuthorNotice.*;
import java.util.function.ToLongFunction;

public interface AuthorNoticePublisher {
    /** Verify the remote PR and author before passing the comment POST to the authorized delivery boundary. */
    boolean publish(Job job, Delivery delivery);

    @FunctionalInterface
    interface Delivery {
        boolean send(long recipientId, ToLongFunction<Evidence> post);
    }
}
