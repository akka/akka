package sample.scala

import javax.ws.rs.{GET, POST, Path, Produces, WebApplicationException, Consumes}
import se.scalablesolutions.akka.kernel.state.{TransactionalState, TransactionalMap, CassandraStorageConfig}
import se.scalablesolutions.akka.kernel.actor.{Supervisor, SupervisorFactory, Actor, StartSupervisor}
import se.scalablesolutions.akka.kernel.config.ScalaConfig._
import javax.ws.rs.core.MultivaluedMap


import _root_.scala.xml.{NodeSeq}
import se.scalablesolutions.akka.kernel.util.{Logging}
import org.atmosphere.core.annotation.{Broadcast, BroadcastFilter, Suspend}
import org.atmosphere.util.{XSSHtmlFilter}


class Boot {
    object factory extends SupervisorFactory {
        override def getSupervisorConfig: SupervisorConfig = {
            SupervisorConfig(
                RestartStrategy(OneForOne, 3, 100),
                  Supervise(
                    new Chat,
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
    @Produces(Array("text/html"))
    def count() = {
        (this !! Tick).getOrElse(<error>Error in counter</error>)
    }

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

@Path("/chat")
class Chat extends Actor with Logging{
    uuid = "Chat"
    makeTransactionRequired

    case class Chat(val who : String, val what : String,val msg : String)
    //case object Suspend

    //private var hasStarted = false;
    //private val storage = TransactionalState.newPersistentMap(CassandraStorageConfig())

    override protected def postRestart(reason: AnyRef, config: Option[AnyRef]) = {
        println("Restarting due to: " + reason.asInstanceOf[Exception].getMessage)
    }

    @Suspend
    @GET
    @Produces(Array("text/html"))
    def suspend() = "<!-- Comet is a programming technique that enables web " +
    "servers to send data to the client without having any need " +
    "for the client to request it. -->\n"

    override def receive: PartialFunction[Any, Unit] = {
        case Chat(who,what,msg) => {

             log.info("Chat(" + who + ", " + what + ", " + msg + ")")
             
             what match {
                 case "login" => reply(<h3>System Message: {who} has joined.</h3>)
                 case "post"  => reply(<p>{who} says: {msg}</p>)
                 case _       => throw new WebApplicationException(422)
             }
        }
       }

        @Broadcast
        @Consumes(Array("application/x-www-form-urlencoded"))
        @POST
        @Produces(Array("text/html"))
        //@BroadcastFilter(Array(classOf[XSSHtmlFilter]))//,classOf[JsonpFilter]))
        def publishMessage(form: MultivaluedMap[String, String]) = (this !! Chat(form.getFirst("name"),form.getFirst("action"),form.getFirst("message"))).getOrElse(<p>Error</p>)
    }