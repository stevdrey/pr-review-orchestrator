package io.github.stevdrey.prrevieworchestrator.ports;

import io.github.stevdrey.prrevieworchestrator.domain.AuthorNotice.*;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.LongFunction;

public interface AuthorNoticePublisher {
    /** Verify the remote PR and author before calling reserve. Send at most once when it yields a permit. */
    OptionalLong publish(Job job, LongFunction<Optional<Evidence>> reserve);
}
