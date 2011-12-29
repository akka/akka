/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.camel.component

import java.net.InetSocketAddress
import java.util.{Map => JMap}
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

import org.apache.camel._
import org.apache.camel.impl.{DefaultProducer, DefaultEndpoint, DefaultComponent}

import akka.actor._
import akka.camel.CamelMessageConversion.toExchangeAdapter

import scala.reflect.BeanProperty
import akka.camel.{CamelServiceManager, Ack, Failure, Message}
import akka.util.{Timeout, Duration}
import akka.dispatch.{Future, Await}

/**
 * @author Martin Krasser
 */
object ActorComponent {
  /**
   * Name of the message header containing the actor id or uuid.
   */
  val ActorIdentifier = "CamelActorIdentifier"
}

/**
 * Camel component for sending messages to and receiving replies from (untyped) actors.
 *
 * @see akka.camel.component.ActorEndpoint
 * @see akka.camel.component.ActorProducer
 *
 * @author Martin Krasser
 */
class ActorComponent extends DefaultComponent {
  def createEndpoint(uri: String, remaining: String, parameters: JMap[String, Object]): ActorEndpoint = {
    val (idType, idValue) = parsePath(remaining)
    new ActorEndpoint(uri, this, idType, idValue)
  }

  private def parsePath(remaining: String): Tuple2[String, Option[String]] = remaining match {
    case null | "" => throw new IllegalArgumentException("invalid path: [%s] - should be <actorid> or id:<actorid> or uuid:<actoruuid>" format remaining)
    case   id if id   startsWith "id:"   => ("id",   parseIdentifier(id substring 3))
    case uuid if uuid startsWith "uuid:" => ("uuid", parseIdentifier(uuid substring 5))
    case   id                            => ("id",   parseIdentifier(id))
  }

  private def parseIdentifier(identifier: String): Option[String] =
    if (identifier.length > 0) Some(identifier) else None
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
                    val idType: String,
                    val idValue: Option[String]) extends DefaultEndpoint(uri, comp) {

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
  def createConsumer(processor: Processor): Consumer =
    throw new UnsupportedOperationException("actor consumer not supported yet")

  /**
   * Creates a new ActorProducer instance initialized with this endpoint.
   */
  def createProducer: ActorProducer = new ActorProducer(this)

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
class ActorProducer(val ep: ActorEndpoint) extends DefaultProducer(ep) with AsyncProcessor {
  import ActorProducer._

  private lazy val uuid = uuidFrom(ep.idValue.getOrElse(throw new ActorIdentifierNotSetException))

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
  import Await._
  val timeout = 10 seconds
  implicit val timeout2 = new Timeout(timeout)

  private def sendSync(exchange: Exchange) = {

    val actor = target(exchange)
    val result: Any = try { Await.result(actor.ask(requestFor(exchange), timeout2),timeout) } catch { case e => Some(Failure(e)) }

    result match {
      case Some(Ack)          => { /* no response message to set */ }
      case Some(msg: Failure) => exchange.fromFailureMessage(msg)
      case Some(msg)          => exchange.fromResponseMessage(Message.canonicalize(msg))
      case None               => throw new TimeoutException("timeout (%d ms) while waiting response from %s"
        format (timeout, ep.getEndpointUri))
    }
  }

  private def sendAsync(exchange: Exchange) = target(exchange) ! requestFor(exchange)
  
  private def sendAsync(exchange: Exchange, callback: AsyncCallback) =
    target(exchange).ask(requestFor(exchange), timeout2).onComplete(AsyncCallbackAdapter(exchange, callback ))

  private def target(exchange: Exchange) =
    targetOption(exchange) getOrElse (throw new ActorNotRegisteredException(ep.getEndpointUri))

  private def targetOption(exchange: Exchange): Option[ActorRef] = ep.idType match {
    case "id"   => targetById(targetId(exchange))
    case "uuid" => targetByUuid(targetUuid(exchange))
  }

  private def targetId(exchange: Exchange) = exchange.getIn.getHeader(ActorComponent.ActorIdentifier) match {
    case id: String  => id
    case null        => ep.idValue.getOrElse(throw new ActorIdentifierNotSetException)
  }

  private def targetUuid(exchange: Exchange) = exchange.getIn.getHeader(ActorComponent.ActorIdentifier) match {
    case uuid: Uuid   => uuid
    case uuid: String => uuidFrom(uuid)
    case null         => uuid
  }

  private def targetById(id: String) = CamelServiceManager.findConsumer(id)
  private def targetByUuid(uuid: Uuid) = CamelServiceManager.findConsumer(uuid)
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

/**
 * @author Martin Krasser
 */
private[akka] object AsyncCallbackAdapter {
  import akka.actor.{Props, ActorSystem, ActorRef, Actor}

  lazy val system = ActorSystem("AsyncCallbackAdapter")
  /**
   * Creates and starts an <code>AsyncCallbackAdapter</code>.
   *
   * @param exchange message exchange to write results to.
   * @param callback callback object to generate completion notifications.
   */
  def apply(exchange: Exchange, callback: AsyncCallback) =  (msg:Any) => {
      msg match {
        case Ack          => { /* no response message to set */ }
        case msg: Failure => exchange.fromFailureMessage(msg)
        case msg          => exchange.fromResponseMessage(Message.canonicalize(msg))
      }
      callback.done(false)
  }
}


