package com.milviv.unit.infrastructure.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/** Схема H2WorkSchema и пользователи в ней — для тестов из других пакетов. */
public final class H2TestUsers {

    private H2TestUsers() {}

    public static void createSchemaWithUsers(Connection connection, UUID... users) throws SQLException {
        try (Statement st = connection.createStatement()) {
            H2WorkSchema.create(st);
        }
        for (UUID user : users) {
            insert(connection, user);
        }
    }

    public static void insert(Connection connection, UUID user) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("INSERT INTO users (id, email, password_hash, name, created_at, updated_at) VALUES ('"
                    + user + "', '" + user + "@test.dev', 'x', 'U', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }
    }
}
