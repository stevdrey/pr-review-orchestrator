package io.github.stevdrey.prrevieworchestrator.domain;

import static io.github.stevdrey.prrevieworchestrator.domain.ReviewTransition.Ci.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.stevdrey.prrevieworchestrator.domain.CiEvidence.*;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CiEvidenceTest {
    private static final PullRequestId PR = new PullRequestId(new RepositoryScope(101, 1001), 7);
    private static final String HEAD = "a".repeat(40), OLD = "b".repeat(40);
    private static final Check CHECK = new Check(2, "build");
    private static final Workflow WORKFLOW = new Workflow(3);
    private static final Run PASS = new Run(CHECK, HEAD, PASSED);

    @Test
    void everyRequiredDependencyMustPassOnTheCurrentHead() {
        assertEquals(UNKNOWN, evidence(PASS, new Run(WORKFLOW, OLD, PASSED)).status());
        assertEquals(PENDING, evidence(PASS, new Run(WORKFLOW, HEAD, PENDING)).status());
        assertEquals(FAILED, evidence(PASS, new Run(WORKFLOW, HEAD, FAILED)).status());
        assertEquals(PASSED, evidence(PASS, new Run(WORKFLOW, HEAD, PASSED),
                new Run(WORKFLOW, OLD, FAILED)).status());
    }

    @Test
    void incompleteMissingOrConflictingEvidenceFailsClosed() {
        var workflowPass = new Run(WORKFLOW, HEAD, PASSED);
        assertEquals(UNKNOWN, evidence(PASS).status());
        assertEquals(UNKNOWN, evidence(PASS, workflowPass, workflowPass).status());
        assertEquals(UNKNOWN, evidence(new Run(new Check(9, "build"), HEAD, PASSED), workflowPass).status());
        assertEquals(UNKNOWN, new CiEvidence(UUID.randomUUID(), PR, HEAD, Set.of(CHECK), List.of(PASS), false).status());
        assertEquals(UNKNOWN, new CiEvidence(UUID.randomUUID(), PR, HEAD, Set.of(), List.of(PASS), true).status());
    }

    private CiEvidence evidence(Run... runs) {
        return new CiEvidence(UUID.randomUUID(), PR, HEAD, Set.of(CHECK, WORKFLOW), List.of(runs), true);
    }
}
