/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.camel.component

import java.util.{Map => JMap}
import java.util.concurrent.TimeoutException

import org.apache.camel._
import org.apache.camel.impl.{DefaultProducer, DefaultEndpoint, DefaultComponent}

import akka.actor._
import akka.camel.CamelMessageConversion.toExchangeAdapter

import scala.reflect.BeanProperty
import akka.camel.{Camel, ConsumerRegistry, Ack, Failure, Message}
import akka.util.Timeout
import akka.dispatch.Await

/**
 * @author Martin Krasser
 */
object ActorComponent {
  /**
   * Name of the message header containing the actor id or uuid.
   */
  val ActorIdentifier = "CamelActorIdentifier"
}

case class Path(value:String)


/**
 * Camel component for sending messages to and receiving replies from (untyped) actors.
 *
 * @see akka.camel.component.ActorEndpoint
 * @see akka.camel.component.ActorProducer
 *
 * @author Martin Krasser
 */
class ActorComponent(camel : Camel with ConsumerRegistry) extends DefaultComponent {
  printf("Starting component '%s' with camel '%s'\n", this, camel)
  def createEndpoint(uri: String, remaining: String, parameters: JMap[String, Object]): ActorEndpoint = {
    val path = parsePath(remaining)
    new ActorEndpoint(uri, this, path, camel)
  }


  private def parsePath(remaining: String): Path = remaining match {
    case null | "" => throw invalidPath(remaining)
    case   id if id   startsWith "path:"   => Path(parseIdentifier(id substring 5).getOrElse(throw invalidPath(remaining)))
  }

  private def parseIdentifier(identifier: String): Option[String] = if (identifier.length > 0) Some(identifier) else None

  private[this] def invalidPath(id: String): scala.IllegalArgumentException = {
    new IllegalArgumentException("invalid path: [%s] - should be <actorid> or id:<actorid> or uuid:<actoruuid>" format id)
  }
}

/**
 * Camel endpoint for sending messages to and receiving replies from (untyped) actors. Actors
 * are referenced using <code>actor</code> endpoint URIs of the following format:
 * <code>actor:<actor-id></code>,
 * <code>actor:id:[<actor-id>]</code> and
 * <code>actor:uuid:[<actor-uuid>]</code>,
 * where <code><actor-id></code> refers to <code>ActorRef.id</code> and <code><actor-uuid></code>
 * refers to the String-representation od <code>ActorRef.uuid</code>. In URIs that contain
 * <code>id:</code> or <code>uuid:</code>, an actor identifier (id or uuid) is optional. In this
 * case, the in-message of an exchange produced to this endpoint must contain a message header
 * with name <code>CamelActorIdentifier</code> and a value that is the target actor's identifier.
 * If the URI contains an actor identifier, a message with a <code>CamelActorIdentifier</code>
 * header overrides the identifier in the endpoint URI.
 *
 * @see akka.camel.component.ActorComponent
 * @see akka.camel.component.ActorProducer

 * @author Martin Krasser
 */
class ActorEndpoint(uri: String,
                    comp: ActorComponent,
                    val path: Path,
                    camel : Camel with ConsumerRegistry) extends DefaultEndpoint(uri, comp) {

  /**
   * Whether to block caller thread during two-way message exchanges with (untyped) actors. This is
   * set via the <code>blocking=true|false</code> endpoint URI parameter. Default value is
   * <code>false</code>.
   */
  @BeanProperty var blocking: Boolean = false

  /**
   * Whether to auto-acknowledge one-way message exchanges with (untyped) actors. This is
   * set via the <code>blocking=true|false</code> endpoint URI parameter. Default value is
   * <code>true</code>. When set to <code>true</code> consumer actors need to additionally
   * call <code>Consumer.ack</code> within <code>Actor.receive</code>.
   */
  @BeanProperty var autoack: Boolean = true

  /**
   * @throws UnsupportedOperationException
   */
  def createConsumer(processor: Processor): org.apache.camel.Consumer =
    throw new UnsupportedOperationException("actor consumer not supported yet")

  /**
   * Creates a new ActorProducer instance initialized with this endpoint.
   */
  def createProducer: ActorProducer = new ActorProducer(this, camel)

  /**
   * Returns true.
   */
  def isSingleton: Boolean = true
}

/**
 * Sends the in-message of an exchange to an (untyped) actor, identified by an
 * actor endpoint URI or by a <code>CamelActorIdentifier</code> message header.
 * <ul>
 * <li>If the exchange pattern is out-capable and <code>blocking</code> is set to
 * <code>true</code> then the producer waits for a reply, using the !! operator.</li>
 * <li>If the exchange pattern is out-capable and <code>blocking</code> is set to
 * <code>false</code> then the producer sends the message using the ! operator, together
 * with a callback handler. The callback handler is an <code>ActorRef</code> that can be
 * used by the receiving actor to asynchronously reply to the route that is sending the
 * message.</li>
 * <li>If the exchange pattern is in-only then the producer sends the message using the
 * ! operator.</li>
 * </ul>
 *
 * @see akka.camel.component.ActorComponent
 * @see akka.camel.component.ActorEndpoint
 *
 * @author Martin Krasser
 */
class ActorProducer(val ep: ActorEndpoint, camel: ConsumerRegistry) extends DefaultProducer(ep) with AsyncProcessor {
  import ActorProducer._

  private lazy val path = ep.path

  def process(exchange: Exchange) =
    if (exchange.getPattern.isOutCapable) sendSync(exchange) else sendAsync(exchange)

  def process(exchange: Exchange, callback: AsyncCallback): Boolean = {
    (exchange.getPattern.isOutCapable, ep.blocking, ep.autoack) match {
      case (true, true, _) => {
        sendSync(exchange)
        callback.done(true)
        true
      }
      case (true, false, _) => {
        sendAsync(exchange, callback)
        false
      }
      case (false, false, true) => {
        sendAsync(exchange)
        callback.done(true)
        true
      }
      case (false, false, false) => {
        sendAsync(exchange, callback)
        false
      }
      case (false, true, false) => {
        sendSync(exchange)
        callback.done(true)
        true
      }
      case (false, true, true) => {
        throw new IllegalStateException("cannot have blocking=true and autoack=true for in-only message exchanges")
      }
    }
  }

  import akka.util.duration._
  val timeout = 10 seconds
  implicit val timeout2 = new Timeout(timeout)

  private def sendSync(exchange: Exchange) = {

    val actor = target(path)
    val result: Any = try { Await.result(actor.ask(requestFor(exchange), timeout2),timeout) } catch { case e => Some(Failure(e)) }

    result match {
      case Some(Ack)          => { /* no response message to set */ }
      case Some(msg: Failure) => exchange.fromFailureMessage(msg)
      case Some(msg)          => exchange.fromResponseMessage(Message.canonicalize(msg))
      case None               => throw new TimeoutException("timeout (%d ms) while waiting response from %s"
        format (timeout, ep.getEndpointUri))
    }
  }

  private def sendAsync(exchange: Exchange) = target(path) ! requestFor(exchange)

  private def sendAsync(exchange: Exchange, callback: AsyncCallback) =
  //TODO: cleanup and decide on timeouts
    target(path).ask(requestFor(exchange), timeout2).onComplete{ msg: Any =>
        msg match {
          case Right(Ack)          => { /* no response message to set */ }
          case Right(msg)          => exchange.fromResponseMessage(Message.canonicalize(msg))
          case Left(msg: Failure)  => exchange.fromFailureMessage(msg)
            //TODO:handle future timeout here
        }
        callback.done(false)
      }


  private def target(path:Path) =
    targetById(path) getOrElse (throw new ActorNotRegisteredException(ep.getEndpointUri))

  private def targetById(path: Path) = camel.findConsumer(path)

}

/**
 * @author Martin Krasser
 */
private[camel] object ActorProducer {
  def requestFor(exchange: Exchange) =
    exchange.toRequestMessage(Map(Message.MessageExchangeId -> exchange.getExchangeId))
}

/**
 * Thrown to indicate that an actor referenced by an endpoint URI cannot be
 * found in the Actor.registry.
 *
 * @author Martin Krasser
 */
class ActorNotRegisteredException(uri: String) extends RuntimeException {
  override def getMessage = "%s not registered" format uri
}

/**
 * Thrown to indicate that no actor identifier has been set.
 *
 * @author Martin Krasser
 */
class ActorIdentifierNotSetException extends RuntimeException {
  override def getMessage = "actor identifier not set"
}



