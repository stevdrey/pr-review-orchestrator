package io.github.stevdrey.prrevieworchestrator;

import io.github.stevdrey.prrevieworchestrator.adapters.operator.RepositoryPolicyCommand;
import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PrReviewOrchestratorApplication {
    public static void main(String[] args) {
        if (args.length > 0 && "repository-policy".equals(args[0])) {
            System.exit(RepositoryPolicyCommand.run(Arrays.copyOfRange(args, 1, args.length)));
            return;
        }
        SpringApplication.run(PrReviewOrchestratorApplication.class, args);
    }
}
