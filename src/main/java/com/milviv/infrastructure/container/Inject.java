package com.milviv.infrastructure.container;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Указка на конструктор для которого можно применить инъекцию зависимостей,
 * контейнером DIContainer. Нужно только если в классе есть несколько конструктов.
 * DIContainer сможет инъектировать зависимости без @Inject, если конструктор один.
 * 
 * @example
 * 
 * class TaskService {
 *  public TaskService() {}
 *  
 *  @Inject
 *  public TaskService(TaskLevelService taskLevelService) {}  
 * }
 * 
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.CONSTRUCTOR)
public @interface Inject {}
