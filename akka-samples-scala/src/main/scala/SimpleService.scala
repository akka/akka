/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package sample.scala

import se.scalablesolutions.akka.kernel.state.{TransactionalState, CassandraStorageConfig}
import se.scalablesolutions.akka.kernel.actor.{SupervisorFactory, Actor}
import se.scalablesolutions.akka.kernel.config.ScalaConfig._
import se.scalablesolutions.akka.kernel.util.Logging

import javax.ws.rs.core.MultivaluedMap
import javax.ws.rs.{GET, POST, Path, Produces, WebApplicationException, Consumes}

import org.atmosphere.core.annotation.{Broadcast, BroadcastFilter => FilterBroadcast, Suspend}
import org.atmosphere.util.XSSHtmlFilter
import org.atmosphere.cpr.BroadcastFilter

class Boot {
  object factory extends SupervisorFactory {
    override def getSupervisorConfig: SupervisorConfig = {
      SupervisorConfig(
        RestartStrategy(OneForOne, 3, 100),
        Supervise(
          new SimpleService,
          LifeCycle(Permanent, 100)) ::
        Supervise(
          new Chat,
          LifeCycle(Permanent, 100)) ::
        Supervise(
           new PersistentSimpleService,
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
  makeTransactionRequired

  case object Tick
  private val KEY = "COUNTER";
  private var hasStartedTicking = false;
  private val storage = TransactionalState.newInMemoryMap[String, Integer]

  @GET
  @Produces(Array("text/html"))
  def count = (this !! Tick).getOrElse(<error>Error in counter</error>)

  override def receive: PartialFunction[Any, Unit] = {
    case Tick => if (hasStartedTicking) {
      val counter = storage.get(KEY).get.asInstanceOf[Integer].intValue
      storage.put(KEY, new Integer(counter + 1))
      reply(<success>Tick:{counter + 1}</success>)
    } else {
      storage.put(KEY, new Integer(0))
      hasStartedTicking = true
      reply(<success>Tick: 0</success>)
    }
  }
}

/**
 * Try service out by invoking (multiple times):
 * <pre>
 * curl http://localhost:9998/persistentscalacount
 * </pre>
 * Or browse to the URL from a web browser.
 */
@Path("/persistentscalacount")
class PersistentSimpleService extends Actor {
  makeTransactionRequired

  case object Tick
  private val KEY = "COUNTER";
  private var hasStartedTicking = false;
  private val storage = TransactionalState.newPersistentMap(CassandraStorageConfig())

  @GET
  @Produces(Array("text/html"))
  def count = (this !! Tick).getOrElse(<error>Error in counter</error>)

  override def receive: PartialFunction[Any, Unit] = {
    case Tick => if (hasStartedTicking) {
      val counter = storage.get(KEY).get.asInstanceOf[Integer].intValue
      storage.put(KEY, new Integer(counter + 1))
      reply(<success>Tick:{counter + 1}</success>)
    } else {
      storage.put(KEY, new Integer(0))
      hasStartedTicking = true
      reply(<success>Tick: 0</success>)
    }
  }
}

@Path("/chat")
class Chat extends Actor with Logging {
  makeTransactionRequired

  case class Chat(val who: String, val what: String, val msg: String)

  @Suspend
  @GET
  @Produces(Array("text/html"))
  @FilterBroadcast(Array(classOf[XSSHtmlFilter], classOf[JsonpFilter]))
  def suspend = <!-- Comet is a programming technique that enables web servers to send data to the client without having any need for the client to request it. -->

  override def receive: PartialFunction[Any, Unit] = {
    case Chat(who, what, msg) => {
      what match {
        case "login" => reply("System Message__" + who + " has joined.")
        case "post" => reply("" + who + "__" + msg)
        case _ => throw new WebApplicationException(422)
      }
    }
    case x => log.info("recieve unknown: " + x)
  }

  @Broadcast
  @Consumes(Array("application/x-www-form-urlencoded"))
  @POST
  @Produces(Array("text/html"))
  @FilterBroadcast(Array(classOf[XSSHtmlFilter], classOf[JsonpFilter]))
  def publishMessage(form: MultivaluedMap[String, String]) = (this !! Chat(form.getFirst("name"), form.getFirst("action"), form.getFirst("message"))).getOrElse("System__error")
}


class JsonpFilter extends BroadcastFilter[String] with Logging {
  def filter(m: String) = {
    var name = m
    var message = ""

    if (m.indexOf("__") > 0) {
      name = m.substring(0, m.indexOf("__"))
      message = m.substring(m.indexOf("__") + 2)
    }

    ("<script type='text/javascript'>\n (window.app || window.parent.app).update({ name: \"" +
            name + "\", message: \"" +
            message +
     "\" }); \n</script>\n")
  }
}
