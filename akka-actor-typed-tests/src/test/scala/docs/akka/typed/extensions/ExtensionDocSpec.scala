package docs.akka.typed.extensions

import akka.actor.typed.ActorSystem
import akka.actor.typed.Extension
import akka.actor.typed.ExtensionId
import akka.actor.typed.scaladsl.Behaviors
import akka.protobufv3.internal.Any

object ExtensionDocSpec {

  //#shared-resource
  class ExpensiveDatabaseConnection {
    def executeQuery(query: String): Any = ???
  }
  //#shared-resource

  //#extension-id
  object DatabaseConnectionPool extends ExtensionId[DatabaseConnectionPool] {
    // will only be called once
    def createExtension(system: ActorSystem[_]): DatabaseConnectionPool = new DatabaseConnectionPool(system)

    // Java API
    def get(system: ActorSystem[_]) = apply(system)
  }
  //#extension-id

  //#extension
  class DatabaseConnectionPool(system: ActorSystem[_]) extends Extension {
    // database configuration can be laoded from config
    // from the actor system
    private val _connection = new ExpensiveDatabaseConnection()

    def connection(): ExpensiveDatabaseConnection = _connection
  }
  //#extension

  //#usage
  Behaviors.setup { ctx =>
    DatabaseConnectionPool(ctx.system).connection().executeQuery("insert into...")
    ???
  }
  //#usage
}
