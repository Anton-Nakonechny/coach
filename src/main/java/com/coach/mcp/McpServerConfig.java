package com.coach.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP server over Streamable HTTP at {@code POST /mcp}. The transport is a plain
 * servlet registered at the exact {@code /mcp} mapping, so it wins over the
 * DispatcherServlet (mapped at {@code /}) and the REST routes are untouched.
 * Prompts only by design — no MCP tools, and no MCP resources so the gitignored
 * exam PDF and doc snapshots under {@code coaches/Claude/} stay unreachable.
 */
@Configuration
public class McpServerConfig {

    static final String MCP_ENDPOINT = "/mcp";

    @Bean
    HttpServletStreamableServerTransportProvider mcpTransport(ObjectMapper objectMapper) {
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                .mcpEndpoint(MCP_ENDPOINT)
                .build();
    }

    @Bean
    ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet(
            HttpServletStreamableServerTransportProvider transport) {
        var registration = new ServletRegistrationBean<>(transport, MCP_ENDPOINT);
        registration.setAsyncSupported(true);
        registration.setLoadOnStartup(1);
        return registration;
    }

    /** Eager bean: wires the prompt handlers into the transport before it serves traffic. */
    @Bean(destroyMethod = "closeGracefully")
    McpSyncServer mcpServer(HttpServletStreamableServerTransportProvider transport, ClaudeQuizPrompt quiz) {
        return McpServer.sync(transport)
                .serverInfo("coach", "0.1.0")
                .capabilities(McpSchema.ServerCapabilities.builder().prompts(false).completions().build())
                .prompts(quiz.promptSpecification())
                .completions(quiz.completionSpecification())
                .build();
    }
}
