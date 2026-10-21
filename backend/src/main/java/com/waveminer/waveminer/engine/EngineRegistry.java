package com.waveminer.waveminer.engine;

import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class EngineRegistry {

    private final ObjectProvider<TranscriptionEngine> provider;

    public EngineRegistry(ObjectProvider<TranscriptionEngine> provider) { this.provider = provider; }

    public List<TranscriptionEngine> all() {
        return provider.orderedStream().toList();
    }

    public TranscriptionEngine get(String id) {
        return all().stream().filter(e -> e.id().equals(id)).findFirst()
                .orElseThrow(() -> new EngineException("未知转写引擎: " + id));
    }
}
