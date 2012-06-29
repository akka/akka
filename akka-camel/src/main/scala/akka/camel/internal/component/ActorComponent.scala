/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel.internal.component

import language.postfixOps

import java.util.{ Map ⇒ JMap }

import org.apache.camel._
import org.apache.camel.impl.{ DefaultProducer, DefaultEndpoint, DefaultComponent }

import akka.actor._
import akka.pattern._

import scala.reflect.BeanProperty
import scala.concurrent.util.duration._
import scala.concurrent.util.Duration
import java.util.concurrent.{ TimeoutException, CountDownLatch }
import akka.camel.internal.CamelExchangeAdapter
import akka.util.{ NonFatal, Timeout }
import akka.camel.{ ActorNotRegisteredException, ConsumerConfig, Camel, Ack, FailureResult, CamelMessage }

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
private[camel] class ActorComponent(camel: Camel) extends DefaultComponent {
  /**
   * @see org.apache.camel.Component
   */
  def createEndpoint(uri: String, remaining: String, parameters: JMap[String, Object]): ActorEndpoint =
    new ActorEndpoint(uri, this, ActorEndpointPath.fromCamelPath(remaining), camel)
}

/**
 * For internal use only.
 * Does what an endpoint does, creates consumers and producers for the component. The `ActorEndpoint` is a Camel [[org.apache.camel.Endpoint]] that is used to
 * receive messages from Camel. Sending messages from the `ActorComponent` is not supported, a [[akka.camel.Producer]] actor should be used instead.
 *
 * The `ActorEndpoint`s are created by the [[akka.camel.internal.component.ActorComponent]].
 *
 * Actors are referenced using actor endpoint URIs of the following format:
 * <code>actor://path:[actorPath]?[options]%s</code>,
 * where <code>[actorPath]</code> refers to the actor path to the actor.
 *
 * @author Martin Krasser
 */
private[camel] class ActorEndpoint(uri: String,
                                   comp: ActorComponent,
                                   val path: ActorEndpointPath,
                                   camel: Camel) extends DefaultEndpoint(uri, comp) with ActorEndpointConfig {

  /**
   * The ActorEndpoint only supports receiving messages from Camel.
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

  @BeanProperty var replyTimeout: Duration = 1 minute // FIXME default should be in config, not code

  /**
   * Whether to auto-acknowledge one-way message exchanges with (untyped) actors. This is
   * set via the <code>autoack=true|false</code> endpoint URI parameter. Default value is
   * <code>true</code>. When set to <code>false</code> consumer actors need to additionally
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
    case Right(failure: FailureResult) ⇒ exchange.setFailure(failure)
    case Right(msg)                    ⇒ exchange.setResponse(CamelMessage.canonicalize(msg))
    case Left(e: TimeoutException)     ⇒ exchange.setFailure(FailureResult(new TimeoutException("Failed to get response from the actor [%s] within timeout [%s]. Check replyTimeout and blocking settings [%s]" format (endpoint.path, endpoint.replyTimeout, endpoint))))
    case Left(throwable)               ⇒ exchange.setFailure(FailureResult(throwable))
  }

  private def forwardAckTo(exchange: CamelExchangeAdapter): PartialFunction[Either[Throwable, Any], Unit] = {
    case Right(Ack)                    ⇒ { /* no response message to set */ }
    case Right(failure: FailureResult) ⇒ exchange.setFailure(failure)
    case Right(msg)                    ⇒ exchange.setFailure(FailureResult(new IllegalArgumentException("Expected Ack or Failure message, but got: [%s] from actor [%s]" format (msg, endpoint.path))))
    case Left(e: TimeoutException)     ⇒ exchange.setFailure(FailureResult(new TimeoutException("Failed to get Ack or Failure response from the actor [%s] within timeout [%s]. Check replyTimeout and blocking settings [%s]" format (endpoint.path, endpoint.replyTimeout, endpoint))))
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
  def toCamelPath(config: ConsumerConfig = consumerConfig): String = "actor://path:%s?%s" format (actorPath, config.toCamelParameters)

  def findActorIn(system: ActorSystem): Option[ActorRef] = {
    val ref = system.actorFor(actorPath)
    if (ref.isTerminated) None else Some(ref)
  }
}
/**
 * For internal use only. Companion of `ActorEndpointPath`
 */
private[camel] object ActorEndpointPath {
  private val consumerConfig: ConsumerConfig = new ConsumerConfig {}

  def apply(actorRef: ActorRef): ActorEndpointPath = new ActorEndpointPath(actorRef.path.toString)

  /**
   * Creates an [[akka.camel.internal.component.ActorEndpointPath]] from the remaining part of the endpoint URI (the part after the scheme, without the parameters of the URI).
   * Expects the remaining part of the URI (the actor path) in a format: path:%s
   */
  def fromCamelPath(camelPath: String): ActorEndpointPath = camelPath match {
    case id if id startsWith "path:" ⇒ new ActorEndpointPath(id substring 5)
    case _                           ⇒ throw new IllegalArgumentException("Invalid path: [%s] - should be path:<actorPath>" format camelPath)
  }
}