import akka.actor.Status.Failure
import akka.actor.{ Actor, ActorRef, Props, ActorSystem }
import akka.camel.{ Producer, CamelMessage, Consumer }
import org.apache.camel.{ Exchange }

/**
 * Asynchronous routing and transformation example
 */
object AsyncRouteAndTransform extends App {
  val system = ActorSystem("rewriteAkkaToAKKA")
  val httpTransformer = system.actorOf(Props[HttpTransformer], "transformer")
  val httpProducer = system.actorOf(Props(new HttpProducer(httpTransformer)), "producer")
  val httpConsumer = system.actorOf(Props(new HttpConsumer(httpProducer)), "consumer")
}

class HttpConsumer(producer: ActorRef) extends Consumer {
  def endpointUri = "jetty:http://0.0.0.0:8875/"
  def receive = {
    case msg ⇒ producer forward msg
  }
}

class HttpProducer(transformer: ActorRef) extends Actor with Producer {
  def endpointUri = "jetty://http://akka.io/?bridgeEndpoint=true"

  override def transformOutgoingMessage(msg: Any) = msg match {
    case msg: CamelMessage ⇒ msg.copy(headers = msg.headers(Set(Exchange.HTTP_PATH)))
  }

  override def routeResponse(msg: Any) {
    transformer forward msg
  }
}

class HttpTransformer extends Actor {
  def receive = {
    case msg: CamelMessage ⇒
      val transformedMsg = msg.mapBody {
        (body: Array[Byte]) ⇒
          new String(body).replaceAll("Akka", "<b>AKKA</b>")
            // just to make the result look a bit better.
            .replaceAll("href=\"/resources", "href=\"http://akka.io/resources")
            .replaceAll("src=\"/resources", "src=\"http://akka.io/resources")
      }
      sender ! transformedMsg
    case msg: Failure ⇒ sender ! msg
  }
}

