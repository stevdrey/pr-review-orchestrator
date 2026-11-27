package io.github.stevdrey.prrevieworchestrator.adapters.mcp;

import io.github.stevdrey.prrevieworchestrator.application.McpTools;
import io.github.stevdrey.prrevieworchestrator.ports.McpToolStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "orchestrator.mcp", name = "enabled", havingValue = "true")
class McpConfiguration {
    @Bean
    McpTools mcpTools(McpToolStore store) {
        return new McpTools(store);
    }
}
