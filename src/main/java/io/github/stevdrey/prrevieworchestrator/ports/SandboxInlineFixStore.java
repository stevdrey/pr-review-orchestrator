package io.github.stevdrey.prrevieworchestrator.ports;

import io.github.stevdrey.prrevieworchestrator.domain.PullRequestId;
import io.github.stevdrey.prrevieworchestrator.domain.SandboxInlineFix;

public interface SandboxInlineFixStore {
    boolean record(PullRequestId pr, SandboxInlineFix proof, Runnable authorize);
}
