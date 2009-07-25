package sample.scala

import javax.ws.rs.{Path, GET, Produces}
import se.scalablesolutions.akka.kernel.state.{TransactionalState, TransactionalMap, CassandraStorageConfig}
import se.scalablesolutions.akka.kernel.actor.{Supervisor, SupervisorFactory, Actor, StartSupervisor}
import se.scalablesolutions.akka.kernel.config.ScalaConfig._

class Boot {
  object factory extends SupervisorFactory {
    override def getSupervisorConfig: SupervisorConfig = {
      SupervisorConfig(
        RestartStrategy(OneForOne, 3, 100),
        Supervise(
          new SimpleService,
          LifeCycle(Permanent, 100))
        :: Nil)
    }
  }
  val supervisor = factory.newSupervisor
  supervisor.startSupervisor
}

/**
 * Try service out by invoking (multiple times):
 * <pre>
 * curl http://localhost:9998/scalacount
 * </pre>
 * Or browse to the URL from a web browser.
 */                         
@Path("/scalacount")
class SimpleService extends Actor {
  uuid = "SimpleService"
  makeTransactionRequired

  case object Tick
  private val KEY = "COUNTER";
  private var hasStartedTicking = false;
  private val storage = TransactionalState.newPersistentMap(CassandraStorageConfig())

  @GET
  @Produces(Array("application/xml"))
  def count = (this !! Tick).getOrElse(<error>Error in counter</error>)

  override def receive: PartialFunction[Any, Unit] = {
    case Tick => if (hasStartedTicking) {
      val counter = storage.get(KEY).get.asInstanceOf[Integer].intValue
      storage.put(KEY, new Integer(counter + 1))
      reply(<success>Tick: {counter + 1}</success>)
    } else {
      storage.put(KEY, new Integer(0))
      hasStartedTicking = true
      reply(<success>Tick: 0</success>)
    }
  }
  
  override protected def postRestart(reason: AnyRef, config: Option[AnyRef]) = {
    println("Restarting due to: " + reason.asInstanceOf[Exception].getMessage)
  }
}