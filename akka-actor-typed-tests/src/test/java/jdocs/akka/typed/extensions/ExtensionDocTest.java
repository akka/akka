package jdocs.akka.typed.extensions;

import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.Extension;
import akka.actor.typed.ExtensionId;
import akka.actor.typed.javadsl.Behaviors;

public class ExtensionDocTest {

   //#shared-resource
   public static class ExpensiveDatabaseConnection {
      public Object executeQuery(String query) {
         throw new RuntimeException("I should do a database query");
      }
      //...
   }
   //#shared-resource

   //#extension
   public static class DatabaseConnectionPool implements Extension {
      private final ExpensiveDatabaseConnection _connection;

      public DatabaseConnectionPool(ActorSystem<?> system) {
         // database configuration can be laoded from config
         // from the actor system
         _connection = new ExpensiveDatabaseConnection();
      }

      public ExpensiveDatabaseConnection connection() {
         return _connection;
      }
   }
   //#extension

   //#extension-id
   public static class DatabaseConnectionPoolId extends ExtensionId<DatabaseConnectionPool> {

      private final static DatabaseConnectionPoolId instance = new DatabaseConnectionPoolId();

      private DatabaseConnectionPool { }

      // called once per ActorSystem
      @Override
      public DatabaseConnectionPool createExtension(ActorSystem system) {
         return new DatabaseConnectionPool(system);
      }


      public static DatabaseConnectionPool get(ActorSystem<?> system) {
         return instance.apply(system);
      }
   }
   //#extension-id

   public static Behavior<Object> initialBehavior() {
      return null;
   }

   public static void usage() {
      //#usage
      Behaviors.setup((context) -> {
         DatabaseConnectionPoolId.get(context.getSystem()).connection().executeQuery("insert into...");
         return initialBehavior();
      });
      //#usage
   }

   public static void checkScalaExtension() {
      docs.akka.typed.extensions.ExtensionDocSpec.DatabaseConnectionPool.get(null);
   }
}
