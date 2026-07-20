package com.coach;

import com.coach.config.AppConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Entry point for the Coach MCP server. Exposes the Claude Architect quiz as an MCP
 * prompt over Streamable HTTP at {@code POST /mcp}. It reuses {@code coach-core}
 * (persona + topic blueprint + official-doc grounding) and never touches the
 * Anthropic SDK or conversation persistence — the MCP host owns the LLM call.
 */
@SpringBootApplication
@EnableConfigurationProperties(AppConfig.class)
public class CoachMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoachMcpApplication.class, args);
    }
}
