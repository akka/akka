/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package sample.rest.scala

import se.scalablesolutions.akka.actor.{Transactor, SupervisorFactory, Actor}
import se.scalablesolutions.akka.actor.Actor._
import se.scalablesolutions.akka.stm.TransactionalMap
import se.scalablesolutions.akka.persistence.cassandra.CassandraStorage
import se.scalablesolutions.akka.config.ScalaConfig._
import se.scalablesolutions.akka.util.Logging
import se.scalablesolutions.akka.comet.AkkaClusterBroadcastFilter
import scala.xml.NodeSeq
import java.lang.Integer
import java.nio.ByteBuffer
import javax.ws.rs.core.MultivaluedMap
import javax.ws.rs.{GET, POST, Path, Produces, WebApplicationException, Consumes,PathParam}
import se.scalablesolutions.akka.actor.ActorRegistry.actorsFor
import org.atmosphere.annotation.{Broadcast, Suspend,Cluster}
import org.atmosphere.util.XSSHtmlFilter
import org.atmosphere.cpr.{Broadcaster, BroadcastFilter}
import org.atmosphere.jersey.Broadcastable

class Boot {
  val factory = SupervisorFactory(
    SupervisorConfig(
      RestartStrategy(OneForOne, 3, 100,List(classOf[Exception])),
      Supervise(
        actorOf[SimpleServiceActor],
        LifeCycle(Permanent)) ::
      Supervise(
        actorOf[ChatActor],
        LifeCycle(Permanent)) ::
      Supervise(
         actorOf[PersistentSimpleServiceActor],
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
class SimpleService {
  @GET
  @Produces(Array("text/html"))
  def count = {
    //Fetch the first actor of type SimpleServiceActor
    //Send it the "Tick" message and expect a NodeSeq back
    val result = for{a <- actorsFor(classOf[SimpleServiceActor]).headOption
                     r <- (a !! "Tick").as[NodeSeq]} yield r
    //Return either the resulting NodeSeq or a default one
    result getOrElse <error>Error in counter</error>
  }
}

class SimpleServiceActor extends Transactor {
  private val KEY = "COUNTER"
  private var hasStartedTicking = false
  private lazy val storage = TransactionalMap[String, Integer]()

  def receive = {
    case "Tick" => if (hasStartedTicking) {
      val counter = storage.get(KEY).get.asInstanceOf[Integer].intValue
      storage.put(KEY, new Integer(counter + 1))
      self.reply(<success>Tick:{counter + 1}</success>)
    } else {
      storage.put(KEY, new Integer(0))
      hasStartedTicking = true
      self.reply(<success>Tick: 0</success>)
    }
  }
}

@Path("/pubsub/")
class PubSub {
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
}

/**
 * Try service out by invoking (multiple times):
 * <pre>
 * curl http://localhost:9998/persistentscalacount
 * </pre>
 * Or browse to the URL from a web browser.
 */
@Path("/persistentscalacount")
class PersistentSimpleService {
  @GET
  @Produces(Array("text/html"))
  def count = {
    //Fetch the first actor of type PersistentSimpleServiceActor
    //Send it the "Tick" message and expect a NodeSeq back
    val result = for{a <- actorsFor(classOf[PersistentSimpleServiceActor]).headOption
                     r <- (a !! "Tick").as[NodeSeq]} yield r
    //Return either the resulting NodeSeq or a default one
    result getOrElse <error>Error in counter</error>
  }
}

class PersistentSimpleServiceActor extends Transactor {
  private val KEY = "COUNTER"
  private var hasStartedTicking = false
  private lazy val storage = CassandraStorage.newMap

  def receive = {
    case "Tick" => if (hasStartedTicking) {
      val bytes = storage.get(KEY.getBytes).get
      val counter = Integer.parseInt(new String(bytes, "UTF8"))
      storage.put(KEY.getBytes, (counter + 1).toString.getBytes )
//      val bytes = storage.get(KEY.getBytes).get
//      val counter = ByteBuffer.wrap(bytes).getInt
//      storage.put(KEY.getBytes, ByteBuffer.allocate(4).putInt(counter + 1).array)
      self.reply(<success>Tick:{counter + 1}</success>)
    } else {
      storage.put(KEY.getBytes, "0".getBytes)
//      storage.put(KEY.getBytes, Array(0.toByte))
      hasStartedTicking = true
      self.reply(<success>Tick: 0</success>)
    }
  }
}

@Path("/chat")
class Chat {
  import ChatActor.ChatMsg
  @Suspend
  @GET
  @Produces(Array("text/html"))
  def suspend = ()

  @POST
  @Broadcast(Array(classOf[XSSHtmlFilter], classOf[JsonpFilter]))
  //FIXME @Cluster(value = Array(classOf[AkkaClusterBroadcastFilter]),name = "bar")
  @Consumes(Array("application/x-www-form-urlencoded"))
  @Produces(Array("text/html"))
  def publishMessage(form: MultivaluedMap[String, String]) = {
    val msg = ChatMsg(form.getFirst("name"),form.getFirst("action"),form.getFirst("message"))
    //Fetch the first actor of type ChatActor
    //Send it the "Tick" message and expect a NodeSeq back
    val result = for{a <- actorsFor(classOf[ChatActor]).headOption
                     r <- (a !! msg).as[String]} yield r
    //Return either the resulting String or a default one
    result getOrElse "System__error"
  }
}

object ChatActor {
  case class ChatMsg(val who: String, val what: String, val msg: String)
}

class ChatActor extends Actor with Logging {
  import ChatActor.ChatMsg
  def receive = {
    case ChatMsg(who, what, msg) => {
      what match {
        case "login" => self.reply("System Message__" + who + " has joined.")
        case "post" => self.reply("" + who + "__" + msg)
        case _ => throw new WebApplicationException(422)
      }
    }
    case x => log.info("recieve unknown: " + x)
  }
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
