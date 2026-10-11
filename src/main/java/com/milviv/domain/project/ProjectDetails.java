package com.milviv.domain.project;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Редактируемые поля проекта. Сложность сюда не входит — её вычисляет система.
 *
 * @param goalId цель; null — без цели
 */
public record ProjectDetails(UUID goalId, String title, String description, LocalDate deadline) {}
