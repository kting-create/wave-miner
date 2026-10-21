package com.waveminer.waveminer.engine;

public interface EngineKeyProvider {
    /** 无配置返回 null */
    String getKey(String engineId);
}
