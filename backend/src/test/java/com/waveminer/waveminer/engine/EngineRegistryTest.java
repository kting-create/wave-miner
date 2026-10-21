package com.waveminer.waveminer.engine;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineRegistryTest {

    @SuppressWarnings("unchecked")
    private EngineRegistry registry(TranscriptionEngine... engines) {
        ObjectProvider<TranscriptionEngine> provider = Mockito.mock(ObjectProvider.class);
        Mockito.when(provider.orderedStream()).thenAnswer(inv -> List.of(engines).stream());
        return new EngineRegistry(provider);
    }

    private TranscriptionEngine fake(String id) {
        TranscriptionEngine e = Mockito.mock(TranscriptionEngine.class);
        Mockito.when(e.id()).thenReturn(id);
        return e;
    }

    @Test
    void getReturnsEngineById() {
        EngineRegistry r = registry(fake("a"), fake("b"));
        assertThat(r.get("b").id()).isEqualTo("b");
        assertThat(r.all()).hasSize(2);
    }

    @Test
    void unknownIdFailsInChinese() {
        EngineRegistry r = registry(fake("a"));
        assertThatThrownBy(() -> r.get("nope"))
                .isInstanceOf(EngineException.class)
                .hasMessageContaining("未知转写引擎");
    }

    @Test
    void emptyRegistryAllowed() {
        assertThat(registry().all()).isEmpty();
    }
}
