/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel.internal.component

import java.util.{ Map ⇒ JMap }

import org.apache.camel._
import org.apache.camel.impl.{ DefaultProducer, DefaultEndpoint, DefaultComponent }

import akka.actor._

import scala.reflect.BeanProperty
import akka.util.{ Duration, Timeout }
import akka.util.duration._
import java.util.concurrent.{ TimeoutException, CountDownLatch }
import akka.camel.{ ConsumerConfig, Camel, Ack, Failure ⇒ CamelFailure, Message }
import akka.camel.internal.CamelExchangeAdapter

/**
 * Camel component for sending messages to and receiving replies from (untyped) actors.
 *
 * @see akka.camel.component.ActorEndpoint
 * @see akka.camel.component.ActorProducer
 *
 * @author Martin Krasser
 */
class ActorComponent(camel: Camel) extends DefaultComponent {
  def createEndpoint(uri: String, remaining: String, parameters: JMap[String, Object]): ActorEndpoint = {
    val path = ActorEndpointPath.fromCamelPath(remaining)
    new ActorEndpoint(uri, this, path, camel)
  }
}

/**
 * TODO fix the doc to be consistent with implementation
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
 *
 * @author Martin Krasser
 */
class ActorEndpoint(uri: String,
                    comp: ActorComponent,
                    val path: ActorEndpointPath,
                    camel: Camel) extends DefaultEndpoint(uri, comp) with ActorEndpointConfig {

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

trait ActorEndpointConfig {
  def path: ActorEndpointPath

  @BeanProperty var replyTimeout: Duration = 1 minute

  /**
   * TODO fix it
   * Whether to auto-acknowledge one-way message exchanges with (untyped) actors. This is
   * set via the <code>blocking=true|false</code> endpoint URI parameter. Default value is
   * <code>true</code>. When set to <code>true</code> consumer actors need to additionally
   * call <code>Consumer.ack</code> within <code>Actor.receive</code>.
   */
  @BeanProperty var autoack: Boolean = true
}

//FIXME: rewrite this doc
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
class ActorProducer(val ep: ActorEndpoint, camel: Camel) extends DefaultProducer(ep) with AsyncProcessor {
  def process(exchange: Exchange) { new ConsumerAsyncProcessor(ep, camel).process(new CamelExchangeAdapter(exchange)) }
  def process(exchange: Exchange, callback: AsyncCallback) = new ConsumerAsyncProcessor(ep, camel).process(new CamelExchangeAdapter(exchange), callback)
}

class ConsumerAsyncProcessor(config: ActorEndpointConfig, camel: Camel) {

  def process(exchange: CamelExchangeAdapter) {
    val isDone = new CountDownLatch(1)
    process(exchange, new AsyncCallback { def done(doneSync: Boolean) { isDone.countDown() } })
    isDone.await() // this should never wait forever as the process(exchange, callback) method guarantees that.
  }

  def process(exchange: CamelExchangeAdapter, callback: AsyncCallback): Boolean = {

    // this notify methods are just a syntax sugar
    def notifyDoneSynchronously[A](a: A = null) = callback.done(true)
    def notifyDoneAsynchronously[A](a: A = null) = callback.done(false)

    def message = messageFor(exchange)

    if (exchange.isOutCapable) { //InOut
      sendAsync(message, onComplete = forwardResponseTo(exchange) andThen notifyDoneAsynchronously)
    } else { // inOnly
      if (config.autoack) { //autoAck
        fireAndForget(message, exchange)
        notifyDoneSynchronously()
        true // done sync
      } else { //manualAck
        sendAsync(message, onComplete = forwardAckTo(exchange) andThen notifyDoneAsynchronously)
      }
    }

  }

  private def sendAsync(message: Message, onComplete: PartialFunction[Either[Throwable, Any], Unit]): Boolean = {
    try {
      val actor = actorFor(config.path)
      val future = actor ? (message, new Timeout(config.replyTimeout))
      future.onComplete(onComplete)
    } catch {
      case e ⇒ onComplete(Left(e))
    }
    false // Done async
  }

  private def fireAndForget(message: Message, exchange: CamelExchangeAdapter) {
    try {
      actorFor(config.path) ! message
    } catch {
      case e ⇒ exchange.setFailure(new CamelFailure(e))
    }
  }

  private[this] def forwardResponseTo(exchange: CamelExchangeAdapter): PartialFunction[Either[Throwable, Any], Unit] = {
    case Right(failure: CamelFailure) ⇒ exchange.setFailure(failure);
    case Right(msg)                   ⇒ exchange.setResponse(Message.canonicalize(msg))
    case Left(e: TimeoutException)    ⇒ exchange.setFailure(CamelFailure(new TimeoutException("Failed to get response from the actor [%s] within timeout [%s]. Check replyTimeout and blocking settings [%s]" format (config.path, config.replyTimeout, config))))
    case Left(throwable)              ⇒ exchange.setFailure(CamelFailure(throwable))
  }

  def forwardAckTo(exchange: CamelExchangeAdapter): PartialFunction[Either[Throwable, Any], Unit] = {
    case Right(Ack)                   ⇒ { /* no response message to set */ }
    case Right(failure: CamelFailure) ⇒ exchange.setFailure(failure)
    case Right(msg)                   ⇒ exchange.setFailure(CamelFailure(new IllegalArgumentException("Expected Ack or Failure message, but got: [%s] from actor [%s]" format (msg, config.path))))
    case Left(e: TimeoutException)    ⇒ exchange.setFailure(CamelFailure(new TimeoutException("Failed to get Ack or Failure response from the actor [%s] within timeout [%s]. Check replyTimeout and blocking settings [%s]" format (config.path, config.replyTimeout, config))))
    case Left(throwable)              ⇒ exchange.setFailure(CamelFailure(throwable))
  }

  private[this] def actorFor(path: ActorEndpointPath): ActorRef =
    path.findActorIn(camel.system) getOrElse (throw new ActorNotRegisteredException(path.actorPath))

  private[this] def messageFor(exchange: CamelExchangeAdapter) =
    exchange.toRequestMessage(Map(Message.MessageExchangeId -> exchange.getExchangeId))

}

/**
 * Thrown to indicate that an actor referenced by an endpoint URI cannot be
 * found in the Actor.registry.
 *
 * @author Martin Krasser
 */
class ActorNotRegisteredException(uri: String) extends RuntimeException {
  override def getMessage = "Actor [%s] doesn't exist" format uri
}

private[camel] object DurationTypeConverter extends CamelTypeConverter {
  def convertTo[T](`type`: Class[T], value: AnyRef) = {
    require(value.toString.endsWith(" nanos"))
    Duration.fromNanos(value.toString.dropRight(6).toLong).asInstanceOf[T]
  }

  def toString(duration: Duration) = duration.toNanos + " nanos"
}

private[camel] abstract class CamelTypeConverter extends TypeConverter {
  def convertTo[T](`type`: Class[T], exchange: Exchange, value: AnyRef) = convertTo(`type`, value)
  def mandatoryConvertTo[T](`type`: Class[T], value: AnyRef) = convertTo(`type`, value)
  def mandatoryConvertTo[T](`type`: Class[T], exchange: Exchange, value: AnyRef) = convertTo(`type`, value)
}

private[camel] case class ActorEndpointPath private (actorPath: String) {
  require(actorPath != null)
  require(actorPath.length() > 0)
  def toCamelPath(config: ConsumerConfig = new ConsumerConfig {}): String = "actor://path:%s?%s" format (actorPath, config.toCamelParameters)

  def findActorIn(system: ActorSystem): Option[ActorRef] = {
    val ref = system.actorFor(actorPath)
    if (ref.isTerminated) None else Some(ref)
  }

}

private[camel] object ActorEndpointPath {
  def apply(actorRef: ActorRef) = new ActorEndpointPath(actorRef.path.toString)

  /**
   * Expects path in a format: path:%s
   */
  def fromCamelPath(camelPath: String) = camelPath match {
    case id if id startsWith "path:" ⇒ new ActorEndpointPath(id substring 5)
    case _                           ⇒ throw new IllegalArgumentException("Invalid path: [%s] - should be path:<actorPath>" format camelPath)
  }
}
