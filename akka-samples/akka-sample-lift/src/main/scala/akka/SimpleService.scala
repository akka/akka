package sample.lift

import se.scalablesolutions.akka.actor._
import se.scalablesolutions.akka.actor.Actor._
import se.scalablesolutions.akka.config.ScalaConfig._
import se.scalablesolutions.akka.stm.TransactionalMap
import se.scalablesolutions.akka.persistence.cassandra.CassandraStorage
import scala.xml.Node
import java.lang.Integer
import javax.ws.rs.{GET, Path, Produces}
import java.nio.ByteBuffer
import net.liftweb.http._
import net.liftweb.http.rest._

class SimpleServiceActor extends Transactor {
  private val KEY = "COUNTER"
  private var hasStartedTicking = false
  private lazy val storage = TransactionalMap[String, Integer]()

  def receive = {
    case "Tick" => if (hasStartedTicking) {
      val counter = storage.get(KEY).get.asInstanceOf[Integer].intValue
      storage.put(KEY, new Integer(counter + 1))
      self.reply(<h1>Tick: {counter + 1}</h1>)
    } else {
      storage.put(KEY, new Integer(0))
      hasStartedTicking = true
      self.reply(<h1>Tick: 0</h1>)
    }
  }
}

class PersistentServiceActor extends Transactor {

  private val KEY = "COUNTER"
  private var hasStartedTicking = false
  private lazy val storage = CassandraStorage.newMap

  def receive = {
    case "Tick" => if (hasStartedTicking) {
      val bytes = storage.get(KEY.getBytes).get
      val counter = ByteBuffer.wrap(bytes).getInt
      storage.put(KEY.getBytes, ByteBuffer.allocate(4).putInt(counter + 1).array)
      self.reply(<success>Tick:{counter + 1}</success>)
    } else {
      storage.put(KEY.getBytes, ByteBuffer.allocate(4).putInt(0).array)
      hasStartedTicking = true
      self.reply(<success>Tick: 0</success>)
    }
  }
}


/**
 * Try service out by invoking (multiple times):
 * <pre>
 * curl http://localhost:8080/liftcount
 * </pre>
 * Or browse to the URL from a web browser.
 */

object SimpleRestService extends RestHelper {
 serve {
   case Get("liftcount" :: _, req) =>
     //Fetch the first actor of type SimpleServiceActor
     //Send it the "Tick" message and expect a Node back
     val result = for( a <- ActorRegistry.actorsFor(classOf[SimpleServiceActor]).headOption;
                       r <- (a !! "Tick").as[Node] ) yield r

     //Return either the resulting NodeSeq or a default one
     (result getOrElse <h1>Error in counter</h1>).asInstanceOf[Node]
  }
}


/**
 * Try service out by invoking (multiple times):
 * <pre>
 * curl http://localhost:8080/persistentliftcount
 * </pre>
 * Or browse to the URL from a web browser.
 */
 object PersistentRestService extends RestHelper {
  serve {
    case Get("persistentliftcount" :: _, req) =>
      //Fetch the first actor of type SimpleServiceActor
      //Send it the "Tick" message and expect a Node back
      val result = for( a <- ActorRegistry.actorsFor(classOf[PersistentServiceActor]).headOption;
                        r <- (a !! "Tick").as[Node] ) yield r

      //Return either the resulting NodeSeq or a default one
      (result getOrElse <h1>Error in counter</h1>).asInstanceOf[Node]
   }
 }