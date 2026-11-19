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

    /** Thrown by a POST function only when no request reached GitHub, so the send permit may be released. */
    final class NotPosted extends RuntimeException {
        public NotPosted() {
            super("Author notification was not posted", null, false, false);
        }
    }
}
