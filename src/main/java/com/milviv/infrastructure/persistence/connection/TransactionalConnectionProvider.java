package com.milviv.infrastructure.persistence.connection;

import java.sql.Connection;

public final class TransactionalConnectionProvider implements ConnectionProvider {
  
  @Override
  public Connection get() {
    return ConnectionHolder.require();
  }
}
