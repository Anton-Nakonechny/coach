package com.coach.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AnthropicClientConfig {

    @Bean
    public AnthropicClient anthropicClient(AppConfig config) {
        return AnthropicOkHttpClient.builder()
                .apiKey(config.anthropicApiKey())
                .build();
    }
}
