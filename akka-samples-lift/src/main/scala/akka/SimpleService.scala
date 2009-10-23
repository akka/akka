package sample.lift

import se.scalablesolutions.akka.state.{PersistentState, TransactionalState, CassandraStorageConfig}
import se.scalablesolutions.akka.actor.{SupervisorFactory, Actor}
import se.scalablesolutions.akka.config.ScalaConfig._
import se.scalablesolutions.akka.util.Logging

import java.lang.Integer
import javax.ws.rs.core.MultivaluedMap
import javax.ws.rs.{GET, POST, Path, Produces, WebApplicationException, Consumes}

/**
 * Try service out by invoking (multiple times):
 * <pre>
 * curl http://localhost:9998/liftcount
 * </pre>
 * Or browse to the URL from a web browser.
 */
@Path("/liftcount")
class SimpleService extends Actor {
  makeTransactionRequired

  case object Tick
  private val KEY = "COUNTER"
  private var hasStartedTicking = false
  private val storage = TransactionalState.newMap[String, Integer]

  @GET
  @Produces(Array("text/html"))
  def count = (this !! Tick).getOrElse(<h1>Error in counter</h1>)

  override def receive: PartialFunction[Any, Unit] = {
    case Tick => if (hasStartedTicking) {
      val counter = storage.get(KEY).get.asInstanceOf[Integer].intValue
      storage.put(KEY, new Integer(counter + 1))
      reply(<h1>Tick: {counter + 1}</h1>)
    } else {
      storage.put(KEY, new Integer(0))
      hasStartedTicking = true
      reply(<h1>Tick: 0</h1>)
    }
  }
}

/**
 * Try service out by invoking (multiple times):
 * <pre>
 * curl http://localhost:9998/persistentliftcount
 * </pre>
 * Or browse to the URL from a web browser.
 */
@Path("/persistentliftcount")
class PersistentSimpleService extends Actor {
  makeTransactionRequired

  case object Tick
  private val KEY = "COUNTER"
  private var hasStartedTicking = false
  private val storage = PersistentState.newMap(CassandraStorageConfig())

  @GET
  @Produces(Array("text/html"))
  def count = (this !! Tick).getOrElse(<h1>Error in counter</h1>)

  override def receive: PartialFunction[Any, Unit] = {
    case Tick => if (hasStartedTicking) {
      val counter = storage.get(KEY).get.asInstanceOf[Integer].intValue
      storage.put(KEY, new Integer(counter + 1))
      reply(<h1>Tick: {counter + 1}</h1>)
    } else {
      storage.put(KEY, new Integer(0))
      hasStartedTicking = true
      reply(<h1>Tick: 0</h1>)
    }
  }
}
