package com.waveminer.waveminer.api;

import com.waveminer.waveminer.engine.EngineInfo;
import com.waveminer.waveminer.engine.EngineKeyProvider;
import com.waveminer.waveminer.engine.TranscriptionEngine;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/engines")
public class EngineController {

    public record EngineView(String id, String displayName, long maxDurationSec, long maxSizeBytes,
                             boolean supportsDiarization, List<String> languages, boolean configured) {}

    private final List<TranscriptionEngine> engines;
    private final EngineKeyProvider keys;

    public EngineController(ObjectProvider<TranscriptionEngine> engines, EngineKeyProvider keys) {
        this.engines = engines.orderedStream().toList();
        this.keys = keys;
    }

    @GetMapping
    public List<EngineView> list() {
        return engines.stream().map(e -> {
            EngineInfo i = e.info();
            return new EngineView(i.id(), i.displayName(), i.maxDurationSec(), i.maxSizeBytes(),
                    i.supportsDiarization(), i.languages(), keys.getKey(i.id()) != null);
        }).toList();
    }
}
