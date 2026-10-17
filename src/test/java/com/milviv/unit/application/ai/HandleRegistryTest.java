package com.milviv.unit.application.ai;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.milviv.application.ai.HandleRegistry;
import com.milviv.domain.ai.AiHandle;
import com.milviv.domain.ai.OperationReport.EntityType;

class HandleRegistryTest {

    @Test
    void handles_haveTypePrefix_andAreNotDerivedFromId() {
        HandleRegistry registry = new HandleRegistry();
        UUID id = UUID.randomUUID();

        assertThat(registry.issue(EntityType.GOAL, id, 0, null)).matches("g_[A-Za-z0-9]{4}").doesNotContain(id.toString().substring(0, 4));
        assertThat(registry.issue(EntityType.PROJECT, UUID.randomUUID(), 0, null)).startsWith("p_");
        assertThat(registry.issue(EntityType.TASK, UUID.randomUUID(), 0, null)).startsWith("t_");
        assertThat(registry.issue(EntityType.REWARD, UUID.randomUUID(), 0, null)).startsWith("r_");
    }

    @Test
    void sameEntity_getsSameHandle_withFreshVersion() {
        HandleRegistry registry = new HandleRegistry();
        UUID id = UUID.randomUUID();

        String first = registry.issue(EntityType.PROJECT, id, 1, null);
        String second = registry.issue(EntityType.PROJECT, id, 2, LocalDate.parse("2026-12-01"));

        assertThat(second).isEqualTo(first);
        assertThat(registry.resolve(first, EntityType.PROJECT)).get()
                .isEqualTo(new AiHandle(first, EntityType.PROJECT, id, 2, LocalDate.parse("2026-12-01"), null));
        assertThat(registry.snapshot()).hasSize(1);
    }

    @Test
    void resolve_checksType_andUnknownHandles() {
        HandleRegistry registry = new HandleRegistry();
        String goal = registry.issue(EntityType.GOAL, UUID.randomUUID(), 0, null);

        assertThat(registry.resolve(goal, EntityType.GOAL)).isPresent();
        assertThat(registry.resolve(goal, EntityType.PROJECT)).isEmpty();
        assertThat(registry.resolve("g_zzzz", EntityType.GOAL)).isEmpty();
        assertThat(registry.resolve(null, EntityType.GOAL)).isEmpty();
    }

    @Test
    void restoredRegistry_resolvesSavedHandles() {
        UUID id = UUID.randomUUID();
        HandleRegistry restored = new HandleRegistry(List.of(new AiHandle("t_Ab12", EntityType.TASK, id, 3, null, null)));

        assertThat(restored.resolve("t_Ab12", EntityType.TASK)).get().extracting(AiHandle::id).isEqualTo(id);
        assertThat(restored.handleOf(id)).contains("t_Ab12");
    }
}
