/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.typed.extensions;

import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.Extension;
import akka.actor.typed.ExtensionId;
import akka.actor.typed.javadsl.Behaviors;
import com.typesafe.config.ConfigFactory;
import docs.akka.typed.extensions.DatabasePool;
import docs.akka.typed.extensions.ExtensionDocSpec;

import java.util.concurrent.CompletionStage;

public class ExtensionDocTest {

  // #shared-resource
  public static class ExpensiveDatabaseConnection {
    public CompletionStage<Object> executeQuery(String query) {
      throw new RuntimeException("I should do a database query");
    }
    // ...
  }
  // #shared-resource

  // #extension
  public static class DatabaseConnectionPool implements Extension {
    private final ExpensiveDatabaseConnection _connection;

    public DatabaseConnectionPool(ActorSystem<?> system) {
      // database configuration can be loaded from config
      // from the actor system
      _connection = new ExpensiveDatabaseConnection();
    }

    public ExpensiveDatabaseConnection connection() {
      return _connection;
    }
  }
  // #extension

  // #extension-id
  public static class DatabaseConnectionPoolId extends ExtensionId<DatabaseConnectionPool> {

    private static final DatabaseConnectionPoolId instance = new DatabaseConnectionPoolId();

    private DatabaseConnectionPoolId() {}

    // called once per ActorSystem
    @Override
    public DatabaseConnectionPool createExtension(ActorSystem system) {
      return new DatabaseConnectionPool(system);
    }

    public static DatabaseConnectionPool get(ActorSystem<?> system) {
      return instance.apply(system);
    }
  }
  // #extension-id

  public static Behavior<Object> initialBehavior() {
    return null;
  }

  public static void usage() {
    // #usage
    Behaviors.setup(
        (context) -> {
          DatabaseConnectionPoolId.get(context.getSystem())
              .connection()
              .executeQuery("insert into...");
          return initialBehavior();
        });
    // #usage
  }

  public static void checkScalaExtension() {
    ActorSystem<?> system = null;
    DatabasePool.get(system);
  }
}
