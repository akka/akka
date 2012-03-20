/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel.internal.component

import java.util.{ Map ⇒ JMap }

import org.apache.camel._
import org.apache.camel.impl.{ DefaultProducer, DefaultEndpoint, DefaultComponent }

import akka.actor._
import akka.pattern._

import scala.reflect.BeanProperty
import akka.util.duration._
import java.util.concurrent.{ TimeoutException, CountDownLatch }
import akka.camel.internal.CamelExchangeAdapter
import akka.util.{ NonFatal, Duration, Timeout }
import akka.camel.{ DefaultConsumerConfig, ConsumerConfig, Camel, Ack, Failure ⇒ CamelFailure, CamelMessage }

/**
 * For internal use only.
 * Camel component for sending messages to and receiving replies from (untyped) actors.
 *
 * @see akka.camel.component.ActorEndpoint
 * @see akka.camel.component.ActorProducer
 *
 * @author Martin Krasser
 */
private[camel] class ActorComponent(camel: Camel) extends DefaultComponent {
  def createEndpoint(uri: String, remaining: String, parameters: JMap[String, Object]): ActorEndpoint = {
    val path = ActorEndpointPath.fromCamelPath(remaining)
    new ActorEndpoint(uri, this, path, camel)
  }
}

/**
 * For internal use only.
 * The ActorEndpoint is a Camel Endpoint that is used to receive messages from Camel through the ActorComponent
 * Actors are referenced using <code>actor</code> endpoint URIs of the following format:
 * <code>actor://path:[actorPath]?[options]%s</code>,
 * where <code>[actorPath]</code> refers to the Actor Path to the Actor.
 *
 * @see akka.camel.component.ActorComponent
 * @see akka.camel.component.ActorProducer
 *
 * @author Martin Krasser
 */
private[camel] class ActorEndpoint(uri: String,
                                   comp: ActorComponent,
                                   val path: ActorEndpointPath,
                                   camel: Camel) extends DefaultEndpoint(uri, comp) with ActorEndpointConfig {

  /**
   *
   * The ActorEndpoint right now only supports receiving messages from Camel.
   * The createProducer method (not to be confused with a producer actor) is used to send messages into the endpoint.
   * The ActorComponent is only there to send to actors registered through an actor endpoint URI.
   * You can use an actor as an endpoint to send to in a camel route (as in, a Camel Consumer Actor). so from(someuri) to (actoruri), but not 'the other way around'.
   * Supporting createConsumer would mean that messages are consumed from an Actor endpoint in a route, and an Actor is not necessarily a producer of messages
   * [[akka.camel.Producer]] Actors can be used for sending messages to some other uri/ component type registered in Camel.
   * @throws UnsupportedOperationException this method is not supported
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

private[camel] trait ActorEndpointConfig {
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

/**
 * Sends the in-message of an exchange to an untyped actor, identified by an [[akka.camel.internal.component.ActorEndPoint]]
 *
 * @see akka.camel.component.ActorComponent
 * @see akka.camel.component.ActorEndpoint
 *
 * @author Martin Krasser
 */
private[camel] class ActorProducer(val endpoint: ActorEndpoint, camel: Camel) extends DefaultProducer(endpoint) with AsyncProcessor {
  /**
   * Processes the exchange.
   * Calls the asynchronous version of the method and waits for the result (blocking)
   * @param exchange the exchange to process
   */
  def process(exchange: Exchange) { processExchangeAdapter(new CamelExchangeAdapter(exchange)) }

  /**
   * Processes the message exchange. the caller supports having the exchange asynchronously processed.
   * If there was a failure processing then the caused Exception would be set on the Exchange.
   *
   * @param exchange the message exchange
   * @param callback the AsyncCallback will be invoked when the processing of the exchange is completed.
   *        If the exchange is completed synchronously, then the callback is also invoked synchronously.
   *        The callback should therefore be careful of starting recursive loop.
   * @return (doneSync) true to continue execute synchronously, false to continue being executed asynchronously
   */
  def process(exchange: Exchange, callback: AsyncCallback): Boolean = { processExchangeAdapter(new CamelExchangeAdapter(exchange), callback) }

  /**
   * For internal use only. Processes the [[akka.camel.internal.CamelExchangeAdapter]]
   * @param exchange the [[akka.camel.internal.CamelExchangeAdapter]]
   */
  private[camel] def processExchangeAdapter(exchange: CamelExchangeAdapter) {
    val isDone = new CountDownLatch(1)
    processExchangeAdapter(exchange, new AsyncCallback { def done(doneSync: Boolean) { isDone.countDown() } })
    isDone.await() // this should never wait forever as the process(exchange, callback) method guarantees that.
  }

  /**
   * For internal use only. Processes the [[akka.camel.internal.CamelExchangeAdapter]].
   * This method is blocking when the exchange is inOnly. The method returns true if it executed synchronously/blocking.
   * @param exchange the [[akka.camel.internal.CamelExchangeAdapter]]
   * @param callback the callback
   * @return (doneSync) true to continue execute synchronously, false to continue being executed asynchronously
   */
  private[camel] def processExchangeAdapter(exchange: CamelExchangeAdapter, callback: AsyncCallback): Boolean = {

    // these notify methods are just a syntax sugar
    def notifyDoneSynchronously[A](a: A = null) = callback.done(true)
    def notifyDoneAsynchronously[A](a: A = null) = callback.done(false)

    def message = messageFor(exchange)

    if (exchange.isOutCapable) { //InOut
      sendAsync(message, onComplete = forwardResponseTo(exchange) andThen notifyDoneAsynchronously)
    } else { // inOnly
      if (endpoint.autoack) { //autoAck
        fireAndForget(message, exchange)
        notifyDoneSynchronously()
        true // done sync
      } else { //manualAck
        sendAsync(message, onComplete = forwardAckTo(exchange) andThen notifyDoneAsynchronously)
      }
    }

  }
  private def forwardResponseTo(exchange: CamelExchangeAdapter): PartialFunction[Either[Throwable, Any], Unit] = {
    case Right(failure: CamelFailure) ⇒ exchange.setFailure(failure);
    case Right(msg)                   ⇒ exchange.setResponse(CamelMessage.canonicalize(msg))
    case Left(e: TimeoutException)    ⇒ exchange.setFailure(CamelFailure(new TimeoutException("Failed to get response from the actor [%s] within timeout [%s]. Check replyTimeout and blocking settings [%s]" format (endpoint.path, endpoint.replyTimeout, endpoint))))
    case Left(throwable)              ⇒ exchange.setFailure(CamelFailure(throwable))
  }

  private def forwardAckTo(exchange: CamelExchangeAdapter): PartialFunction[Either[Throwable, Any], Unit] = {
    case Right(Ack)                   ⇒ { /* no response message to set */ }
    case Right(failure: CamelFailure) ⇒ exchange.setFailure(failure)
    case Right(msg)                   ⇒ exchange.setFailure(CamelFailure(new IllegalArgumentException("Expected Ack or Failure message, but got: [%s] from actor [%s]" format (msg, endpoint.path))))
    case Left(e: TimeoutException)    ⇒ exchange.setFailure(CamelFailure(new TimeoutException("Failed to get Ack or Failure response from the actor [%s] within timeout [%s]. Check replyTimeout and blocking settings [%s]" format (endpoint.path, endpoint.replyTimeout, endpoint))))
    case Left(throwable)              ⇒ exchange.setFailure(CamelFailure(throwable))
  }

  private def sendAsync(message: CamelMessage, onComplete: PartialFunction[Either[Throwable, Any], Unit]): Boolean = {
    try {
      val actor = actorFor(endpoint.path)
      val future = actor.ask(message)(new Timeout(endpoint.replyTimeout))
      future.onComplete(onComplete)
    } catch {
      case NonFatal(e) ⇒ onComplete(Left(e))
    }
    false // Done async
  }

  private def fireAndForget(message: CamelMessage, exchange: CamelExchangeAdapter) {
    try {
      actorFor(endpoint.path) ! message
    } catch {
      case e ⇒ exchange.setFailure(new CamelFailure(e))
    }
  }

  private[this] def actorFor(path: ActorEndpointPath): ActorRef =
    path.findActorIn(camel.system) getOrElse (throw new ActorNotRegisteredException(path.actorPath))

  private[this] def messageFor(exchange: CamelExchangeAdapter) =
    exchange.toRequestMessage(Map(CamelMessage.MessageExchangeId -> exchange.getExchangeId))

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

/**
 * For internal use only.
 */
private[camel] object DurationTypeConverter extends CamelTypeConverter {
  def convertTo[T](`type`: Class[T], value: AnyRef) = {
    Duration(value.toString).asInstanceOf[T]
  }

  def toString(duration: Duration) = duration.toNanos + " nanos"
}

/**
 * For internal use only.
 */
private[camel] abstract class CamelTypeConverter extends TypeConverter {
  def convertTo[T](`type`: Class[T], exchange: Exchange, value: AnyRef) = convertTo(`type`, value)
  def mandatoryConvertTo[T](`type`: Class[T], value: AnyRef) = convertTo(`type`, value)
  def mandatoryConvertTo[T](`type`: Class[T], exchange: Exchange, value: AnyRef) = convertTo(`type`, value)
}

/**
 * For internal use only. An endpoint to an <code>ActorRef</code>
 * @param actorPath the path to the actor
 */
private[camel] case class ActorEndpointPath private (actorPath: String) {
  require(actorPath != null)
  require(actorPath.length() > 0)
  def toCamelPath(config: ConsumerConfig = DefaultConsumerConfig): String = "actor://path:%s?%s" format (actorPath, config.toCamelParameters)

  def findActorIn(system: ActorSystem): Option[ActorRef] = {
    val ref = system.actorFor(actorPath)
    if (ref.isTerminated) None else Some(ref)
  }

}

/**
 * For internal use only. Companion of <code>ActorEndpointPath</code>
 */
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
