/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package sample.rest.scala

import se.scalablesolutions.akka.actor.{Transactor, SupervisorFactory, Actor}
import se.scalablesolutions.akka.stm.TransactionalState
import se.scalablesolutions.akka.persistence.cassandra.CassandraStorage
import se.scalablesolutions.akka.config.ScalaConfig._
import se.scalablesolutions.akka.util.Logging
import se.scalablesolutions.akka.comet.AkkaClusterBroadcastFilter

import java.lang.Integer
import java.nio.ByteBuffer
import javax.ws.rs.core.MultivaluedMap
import javax.ws.rs.{GET, POST, Path, Produces, WebApplicationException, Consumes,PathParam}

import org.atmosphere.annotation.{Broadcast, Suspend,Cluster}
import org.atmosphere.util.XSSHtmlFilter
import org.atmosphere.cpr.{Broadcaster, BroadcastFilter}
import org.atmosphere.jersey.Broadcastable

class Boot {
  val factory = SupervisorFactory(
    SupervisorConfig(
      RestartStrategy(OneForOne, 3, 100,List(classOf[Exception])),
      Supervise(
        new SimpleService,
        LifeCycle(Permanent)) ::
      Supervise(
        new Chat,
        LifeCycle(Permanent)) ::
      Supervise(
         new PersistentSimpleService,
         LifeCycle(Permanent)) ::
      Supervise(
         new PubSub,
         LifeCycle(Permanent))
      :: Nil))
  factory.newInstance.start
}

/**
 * Try service out by invoking (multiple times):
 * <pre>
 * curl http://localhost:9998/scalacount
 * </pre>
 * Or browse to the URL from a web browser.
 */
@Path("/scalacount")
class SimpleService extends Transactor {

  case object Tick
  private val KEY = "COUNTER"
  private var hasStartedTicking = false
  private lazy val storage = TransactionalState.newMap[String, Integer]

  @GET
  @Produces(Array("text/html"))
  def count = (this !! Tick).getOrElse(<error>Error in counter</error>)

  def receive = {
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

@Path("/pubsub/")
class PubSub extends Actor {
  case class Msg(topic: String, message: String)

  @GET
  @Suspend
  @Produces(Array("text/plain;charset=ISO-8859-1"))
  @Path("/topic/{topic}/")
  def subscribe(@PathParam("topic") topic: Broadcaster): Broadcastable = new Broadcastable("", topic)

  @GET
  @Broadcast
  @Path("/topic/{topic}/{message}/")
  @Produces(Array("text/plain;charset=ISO-8859-1"))
  //FIXME @Cluster(value = Array(classOf[AkkaClusterBroadcastFilter]),name = "foo")
  def say(@PathParam("topic") topic: Broadcaster, @PathParam("message") message: String): Broadcastable = new Broadcastable(message, topic)

  def receive = { case _ => }
}

/**
 * Try service out by invoking (multiple times):
 * <pre>
 * curl http://localhost:9998/persistentscalacount
 * </pre>
 * Or browse to the URL from a web browser.
 */
@Path("/persistentscalacount")
class PersistentSimpleService extends Transactor {

  case object Tick
  private val KEY = "COUNTER"
  private var hasStartedTicking = false
  private lazy val storage = CassandraStorage.newMap

  @GET
  @Produces(Array("text/html"))
  def count = (this !! Tick).getOrElse(<error>Error in counter</error>)

  def receive = {
    case Tick => if (hasStartedTicking) {
      val bytes = storage.get(KEY.getBytes).get
      val counter = ByteBuffer.wrap(bytes).getInt
      storage.put(KEY.getBytes, ByteBuffer.allocate(4).putInt(counter + 1).array)
      reply(<success>Tick:{counter + 1}</success>)
    } else {
      storage.put(KEY.getBytes, Array(0.toByte))
      hasStartedTicking = true
      reply(<success>Tick: 0</success>)
    }
  }
}

@Path("/chat")
class Chat extends Actor with Logging {
  case class Chat(val who: String, val what: String, val msg: String)

  @Suspend
  @GET
  @Produces(Array("text/html"))
  def suspend = ()

  def receive = {
    case Chat(who, what, msg) => {
      what match {
        case "login" => reply("System Message__" + who + " has joined.")
        case "post" => reply("" + who + "__" + msg)
        case _ => throw new WebApplicationException(422)
      }
    }
    case x => log.info("recieve unknown: " + x)
  }

  @POST
  @Broadcast(Array(classOf[XSSHtmlFilter], classOf[JsonpFilter]))
  //FIXME @Cluster(value = Array(classOf[AkkaClusterBroadcastFilter]),name = "bar")
  @Consumes(Array("application/x-www-form-urlencoded"))
  @Produces(Array("text/html"))
  def publishMessage(form: MultivaluedMap[String, String]) =
    (this !! Chat(form.getFirst("name"),
                  form.getFirst("action"),
                  form.getFirst("message"))).getOrElse("System__error")
}


class JsonpFilter extends BroadcastFilter with Logging {
  def filter(an: AnyRef) = {
    val m = an.toString
    var name = m
    var message = ""

    if (m.indexOf("__") > 0) {
      name = m.substring(0, m.indexOf("__"))
      message = m.substring(m.indexOf("__") + 2)
    }

    new BroadcastFilter.BroadcastAction("<script type='text/javascript'>\n (window.app || window.parent.app).update({ name: \"" +
    name + "\", message: \"" + message + "\" }); \n</script>\n")
  }
}
