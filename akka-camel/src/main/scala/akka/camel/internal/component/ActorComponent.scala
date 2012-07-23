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
import java.util.concurrent.{ TimeoutException, CountDownLatch }
import akka.camel.internal.CamelExchangeAdapter
import akka.util.{ NonFatal, Duration, Timeout }
import akka.camel.{ ActorNotRegisteredException, Camel, Ack, FailureResult, CamelMessage }
import java.util.concurrent.TimeUnit.MILLISECONDS
/**
 * For internal use only.
 * Creates Camel [[org.apache.camel.Endpoint]]s that send messages to [[akka.camel.Consumer]] actors through an [[akka.camel.internal.component.ActorProducer]].
 * The `ActorComponent` is a Camel [[org.apache.camel.Component]].
 *
 * Camel integrates with Akka through this component. The [[akka.camel.internal.DefaultCamel]] module adds the
 * `ActorComponent` to the [[org.apache.camel.CamelContext]] under the 'actor' component name.
 * Messages are sent to [[akka.camel.Consumer]] actors through a [[akka.camel.internal.component.ActorEndpoint]] that
 * this component provides.
 *
 * @author Martin Krasser
 */
private[camel] class ActorComponent(camel: Camel, system: ActorSystem) extends DefaultComponent {
  /**
   * @see org.apache.camel.Component
   */
  def createEndpoint(uri: String, remaining: String, parameters: JMap[String, Object]): ActorEndpoint =
    new ActorEndpoint(uri, this, ActorEndpointPath.fromCamelPath(uri), camel)
}

/**
 * For internal use only.
 * Does what an endpoint does, creates consumers and producers for the component. The `ActorEndpoint` is a Camel [[org.apache.camel.Endpoint]] that is used to
 * receive messages from Camel. Sending messages from the `ActorComponent` is not supported, a [[akka.camel.Producer]] actor should be used instead.
 *
 * The `ActorEndpoint`s are created by the [[akka.camel.internal.component.ActorComponent]].
 *
 * Actors are referenced using actor endpoint URIs of the following format:
 * <code>[actorPath]?[options]%s</code>,
 * where <code>[actorPath]</code> refers to the actor path to the actor.
 *
 * @author Martin Krasser
 */
private[camel] class ActorEndpoint(uri: String,
                                   comp: ActorComponent,
                                   val path: ActorEndpointPath,
                                   val camel: Camel) extends DefaultEndpoint(uri, comp) with ActorEndpointConfig {

  /**
   * The ActorEndpoint only supports receiving messages from Camel.
   * The createProducer method (not to be confused with a producer actor) is used to send messages into the endpoint.
   * The ActorComponent is only there to send to actors registered through an actor endpoint URI.
   * You can use an actor as an endpoint to send to in a camel route (as in, a Camel Consumer Actor). so from(someuri) to (actoruri), but not 'the other way around'.
   * Supporting createConsumer would mean that messages are consumed from an Actor endpoint in a route, and an Actor is not necessarily a producer of messages.
   * [[akka.camel.Producer]] Actors can be used for sending messages to some other uri/ component type registered in Camel.
   * @throws UnsupportedOperationException this method is not supported
   */
  def createConsumer(processor: Processor): org.apache.camel.Consumer =
    throw new UnsupportedOperationException("actor consumer not supported yet")

  /**
   * Creates a new producer which is used send messages into the endpoint.
   *
   * @return a newly created producer
   */
  def createProducer: ActorProducer = new ActorProducer(this, camel)

  /**
   * Returns true.
   */
  def isSingleton: Boolean = true
}

/**
 * For internal use only.
 * Configures the `ActorEndpoint`. This needs to be a `bean` for Camel purposes.
 *
 */
private[camel] trait ActorEndpointConfig {
  def path: ActorEndpointPath
  def camel: Camel

  @BeanProperty var replyTimeout: Duration = camel.settings.replyTimeout

  @BeanProperty var autoAck: Boolean = camel.settings.autoAck
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
   * Calls the asynchronous version of the method and waits for the result (blocking).
   * @param exchange the exchange to process
   */
  def process(exchange: Exchange): Unit = processExchangeAdapter(new CamelExchangeAdapter(exchange))

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
  def process(exchange: Exchange, callback: AsyncCallback): Boolean = processExchangeAdapter(new CamelExchangeAdapter(exchange), callback)

  /**
   * For internal use only. Processes the [[akka.camel.internal.CamelExchangeAdapter]]
   * @param exchange the [[akka.camel.internal.CamelExchangeAdapter]]
   *
   * WARNING UNBOUNDED BLOCKING AWAITS
   */
  private[camel] def processExchangeAdapter(exchange: CamelExchangeAdapter): Unit = {
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
    def notifyDoneSynchronously[A](a: A = null): Unit = callback.done(true)
    def notifyDoneAsynchronously[A](a: A = null): Unit = callback.done(false)

    def message: CamelMessage = messageFor(exchange)

    if (exchange.isOutCapable) { //InOut
      sendAsync(message, onComplete = forwardResponseTo(exchange) andThen notifyDoneAsynchronously)
    } else { // inOnly
      if (endpoint.autoAck) { //autoAck
        fireAndForget(message, exchange)
        notifyDoneSynchronously()
        true // done sync
      } else { //manualAck
        sendAsync(message, onComplete = forwardAckTo(exchange) andThen notifyDoneAsynchronously)
      }
    }

  }
  private def forwardResponseTo(exchange: CamelExchangeAdapter): PartialFunction[Either[Throwable, Any], Unit] = {
    case Right(failure: FailureResult) ⇒ exchange.setFailure(failure)
    case Right(msg)                    ⇒ exchange.setResponse(CamelMessage.canonicalize(msg))
    case Left(e: TimeoutException)     ⇒ exchange.setFailure(FailureResult(new TimeoutException("Failed to get response from the actor [%s] within timeout [%s]. Check replyTimeout [%s]" format (endpoint.path, endpoint.replyTimeout, endpoint))))
    case Left(throwable)               ⇒ exchange.setFailure(FailureResult(throwable))
  }

  private def forwardAckTo(exchange: CamelExchangeAdapter): PartialFunction[Either[Throwable, Any], Unit] = {
    case Right(Ack)                    ⇒ { /* no response message to set */ }
    case Right(failure: FailureResult) ⇒ exchange.setFailure(failure)
    case Right(msg)                    ⇒ exchange.setFailure(FailureResult(new IllegalArgumentException("Expected Ack or Failure message, but got: [%s] from actor [%s]" format (msg, endpoint.path))))
    case Left(e: TimeoutException)     ⇒ exchange.setFailure(FailureResult(new TimeoutException("Failed to get Ack or Failure response from the actor [%s] within timeout [%s]. Check replyTimeout [%s]" format (endpoint.path, endpoint.replyTimeout, endpoint))))
    case Left(throwable)               ⇒ exchange.setFailure(FailureResult(throwable))
  }

  private def sendAsync(message: CamelMessage, onComplete: PartialFunction[Either[Throwable, Any], Unit]): Boolean = {
    try {
      actorFor(endpoint.path).ask(message)(Timeout(endpoint.replyTimeout)).onComplete(onComplete)
    } catch {
      case NonFatal(e) ⇒ onComplete(Left(e))
    }
    false // Done async
  }

  private def fireAndForget(message: CamelMessage, exchange: CamelExchangeAdapter): Unit =
    try { actorFor(endpoint.path) ! message } catch { case NonFatal(e) ⇒ exchange.setFailure(new FailureResult(e)) }

  private[this] def actorFor(path: ActorEndpointPath): ActorRef =
    path.findActorIn(camel.system) getOrElse (throw new ActorNotRegisteredException(path.actorPath))

  private[this] def messageFor(exchange: CamelExchangeAdapter) =
    exchange.toRequestMessage(Map(CamelMessage.MessageExchangeId -> exchange.getExchangeId))
}

/**
 * For internal use only. Converts Strings to [[akka.util.Duration]]s
 */
private[camel] object DurationTypeConverter extends TypeConverter {
  override def convertTo[T](`type`: Class[T], value: AnyRef): T = `type`.cast(try {
    val d = Duration(value.toString)
    if (`type`.isInstance(d)) d else null
  } catch {
    case NonFatal(_) ⇒ null
  })

  def convertTo[T](`type`: Class[T], exchange: Exchange, value: AnyRef): T = convertTo(`type`, value)
  def mandatoryConvertTo[T](`type`: Class[T], value: AnyRef): T = convertTo(`type`, value) match {
    case null ⇒ throw new NoTypeConversionAvailableException(value, `type`)
    case some ⇒ some
  }
  def mandatoryConvertTo[T](`type`: Class[T], exchange: Exchange, value: AnyRef): T = mandatoryConvertTo(`type`, value)
  def toString(duration: Duration): String = duration.toNanos + " nanos"
}

/**
 * For internal use only. An endpoint to an [[akka.actor.ActorRef]]
 * @param actorPath the String representation of the path to the actor
 */
private[camel] case class ActorEndpointPath private (actorPath: String) {
  import ActorEndpointPath._
  require(actorPath != null)
  require(actorPath.length() > 0)
  require(actorPath.startsWith("akka://"))

  def findActorIn(system: ActorSystem): Option[ActorRef] = {
    val ref = system.actorFor(actorPath)
    if (ref.isTerminated) None else Some(ref)
  }
}

/**
 * Converts ActorRefs and actorPaths to URI's that point to the actor through the Camel Actor Component.
 * Can also be used in the Java API as a helper for custom route builders. the Scala API has an implicit conversion in the camel package to
 * directly use `to(actorRef)`. In java you could use `to(CamelPath.toUri(actorRef)`.
 * The URI to the actor is exactly the same as the string representation of the ActorPath, except that it can also have optional URI parameters to configure the Consumer Actor.
 */
object CamelPath {
  /**
   * Converts the actorRef to a Camel URI (string) which can be used in custom routes.
   * The created URI will have no parameters, it is purely the string representation of the actor's path.
   * @param actorRef the actorRef
   * @return the Camel URI to the actor.
   */
  def toUri(actorRef: ActorRef): String = actorRef.path.toString

  /**
   * Converts the actorRef to a Camel URI (string) which can be used in custom routes.
   * Use this version of toUri when you know that the actorRef points to a Consumer Actor and you would like to
   * set autoAck and replyTimeout parameters to non-default values.
   *
   * @param actorRef the actorRef
   * @param autoAck parameter for a Consumer Actor, see [[akka.camel.ConsumerConfig]]
   * @param replyTimeout parameter for a Consumer Actor, see [[akka.camel.ConsumerConfig]]
   * @return the Camel URI to the Consumer Actor, including the parameters for auto acknowledgement and replyTimeout.
   */
  def toUri(actorRef: ActorRef, autoAck: Boolean, replyTimeout: Duration): String = "%s?autoAck=%s&replyTimeout=%s".format(actorRef.path.toString, autoAck, replyTimeout.toString)
}

/**
 * For internal use only. Companion of `ActorEndpointPath`
 */
private[camel] case object ActorEndpointPath {

  def apply(actorRef: ActorRef): ActorEndpointPath = new ActorEndpointPath(actorRef.path.toString)

  /**
   * Creates an [[akka.camel.internal.component.ActorEndpointPath]] from the uri
   * Expects the uri in the akka [[akka.actor.ActorPath]] format, i.e 'akka://system/user/someactor'.
   * parameters can be optionally added to the actor path to indicate auto-acknowledgement and replyTimeout for a [[akka.camel.Consumer]] actor.
   */
  def fromCamelPath(camelPath: String): ActorEndpointPath = camelPath match {
    case id if id startsWith "akka://" ⇒ new ActorEndpointPath(id.split('?')(0))
    case _                             ⇒ throw new IllegalArgumentException("Invalid path: [%s] - should be an actorPath starting with 'akka://', optionally followed by options" format camelPath)
  }
}
