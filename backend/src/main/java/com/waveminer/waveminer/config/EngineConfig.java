package com.waveminer.waveminer.config;

import com.waveminer.waveminer.engine.EngineHttpClient;
import com.waveminer.waveminer.engine.EngineKeyProvider;
import com.waveminer.waveminer.engine.MinimaxAsrEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EngineConfig {

    @Bean
    public MinimaxAsrEngine minimaxAsrEngine(EngineHttpClient http, EngineKeyProvider keys, AppProperties props) {
        return new MinimaxAsrEngine(http, keys, props);
    }
}
