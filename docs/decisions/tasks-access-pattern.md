Task — Access Patterns

1. Получить список задач пользователя
   WHERE user_id
   ORDER BY created_at DESC
   LIMIT N

2. Получить задачи проекта
   WHERE project_id
   ORDER BY created_at DESC

3. Получить активные задачи с дедлайном
   WHERE status = ACTIVE
   AND deadline < NOW()

4. Получить конкретную задачу
   WHERE id = ?

5. Получить задачи пользователя определённого статуса
   WHERE user_id = ?
   AND status = ?
   ORDER BY created_at DESC