/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel.internal.component

import java.util.{Map => JMap}

import org.apache.camel._
import org.apache.camel.impl.{DefaultProducer, DefaultEndpoint, DefaultComponent}

import akka.actor._

import scala.reflect.BeanProperty
import akka.dispatch.Await
import akka.util.{Duration, Timeout}
import akka.util.duration._
import java.util.concurrent.TimeoutException
import akka.camel.{ConsumerConfig, Camel, CamelExchangeAdapter, Ack, Failure, Message, CommunicationStyle, Blocking, NonBlocking}

/**
 * Camel component for sending messages to and receiving replies from (untyped) actors.
 *
 * @see akka.camel.component.ActorEndpoint
 * @see akka.camel.component.ActorProducer
 *
 * @author Martin Krasser
 */
class ActorComponent(camel : Camel) extends DefaultComponent {
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

 * @author Martin Krasser
 */
class ActorEndpoint(uri: String,
                    comp: ActorComponent,
                    val path: ActorEndpointPath,
                    camel : Camel) extends DefaultEndpoint(uri, comp)  with ActorEndpointConfig{



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

trait ActorEndpointConfig{
  def path : ActorEndpointPath

  @BeanProperty var replyTimeout: Duration = 1 minute


  /**
   * Whether to block caller thread during two-way message exchanges with (untyped) actors. This is
   * set via the <code>blocking=true|false</code> endpoint URI parameter. Default value is
   * <code>false</code>.
   */
  @BeanProperty var communicationStyle: CommunicationStyle = NonBlocking

  /** TODO fix it
   * Whether to auto-acknowledge one-way message exchanges with (untyped) actors. This is
   * set via the <code>blocking=true|false</code> endpoint URI parameter. Default value is
   * <code>true</code>. When set to <code>true</code> consumer actors need to additionally
   * call <code>Consumer.ack</code> within <code>Actor.receive</code>.
   */
  @BeanProperty var autoack: Boolean = true
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
class ActorProducer(val ep: ActorEndpoint, camel: Camel) extends DefaultProducer(ep) with AsyncProcessor {
  def process(exchange: Exchange) {new ConsumerAsyncProcessor(ep, camel).process(new CamelExchangeAdapter(exchange))}
  def process(exchange: Exchange, callback: AsyncCallback) = new ConsumerAsyncProcessor(ep, camel).process(new CamelExchangeAdapter(exchange), callback)
}

class ConsumerAsyncProcessor(config : ActorEndpointConfig, camel : Camel) {

  def process(exchange: CamelExchangeAdapter) {
    if (exchange.isOutCapable)
      sendSync(exchange, config.replyTimeout, forwardResponseTo(exchange))
    else
      fireAndForget(exchange)
  }

  def process(exchange: CamelExchangeAdapter, callback: AsyncCallback): Boolean = {
    // Following 4 methods are here to make the rest of the code more readable.
    def notifyDoneSynchronously[A](a:A = null) = callback.done(true)
    def notifyDoneAsynchronously[A](a:A = null) = callback.done(false)
    val DoneSync = true
    val DoneAsync = false


    def outCapable: Boolean = {
      config.communicationStyle match {
        case Blocking(timeout) => {
          sendSync(exchange, timeout, onComplete = forwardResponseTo(exchange))
          notifyDoneSynchronously()
          DoneSync
        }
        case NonBlocking => {
          sendAsync(exchange, config.replyTimeout, onComplete = forwardResponseTo(exchange) andThen notifyDoneAsynchronously)
          DoneAsync
        }
      }
    }

    def inOnlyAutoAck: Boolean = {
      config.communicationStyle match {
        case NonBlocking => {
          fireAndForget(exchange)
          notifyDoneAsynchronously()
          DoneAsync
        }
        case Blocking(_) => throw new IllegalStateException("Cannot be blocking and autoack for in-only message exchanges.")
      }
    }

    def inOnlyManualAck: Boolean = {
      config.communicationStyle match {
        case NonBlocking => {
          sendAsync(exchange, config.replyTimeout, onComplete = forwardAckTo(exchange) andThen notifyDoneAsynchronously)
          DoneAsync
        }
        case Blocking(timeout) => {
          sendSync(exchange, timeout, onComplete = forwardAckTo(exchange))
          notifyDoneSynchronously()
          DoneSync
        }
      }
    }

    if (exchange.isOutCapable){
      outCapable
    } else {
      if (config.autoack) inOnlyAutoAck else inOnlyManualAck
    }
  }

  private def sendSync(exchange: CamelExchangeAdapter, timeout : Duration, onComplete: PartialFunction[Either[Throwable, Any], Unit]) {
    val future = send(exchange, timeout)
    val response = either(Await.result(future, timeout))
    onComplete(response)
  }

  private def sendAsync(exchange: CamelExchangeAdapter, timeout : Duration, onComplete: PartialFunction[Either[Throwable, Any], Unit]) {
    val future = send(exchange, timeout)
    future.onComplete(onComplete)
  }

  private def fireAndForget(exchange: CamelExchangeAdapter) { actorFor(config.path) ! messageFor(exchange) }

  private[this] def send(exchange: CamelExchangeAdapter, timeout : Duration) = {
    val actor = actorFor(config.path)
    val message = messageFor(exchange)
    actor ? (message, new Timeout(timeout))
  }

  private[this] def forwardResponseTo(exchange:CamelExchangeAdapter) : PartialFunction[Either[Throwable,Any], Unit] = {
    case Right(failure:Failure) => exchange.setFailure(failure);
    case Right(msg) => exchange.setResponse(Message.canonicalize(msg, camel))
    case Left(e:TimeoutException) =>  exchange.setFailure(Failure(new TimeoutException("Failed to get response from the actor within timeout. Check replyTimeout and blocking settings.")))
    case Left(throwable) =>  exchange.setFailure(Failure(throwable))
  }

  def forwardAckTo(exchange:CamelExchangeAdapter) : PartialFunction[Either[Throwable,Any], Unit] = {
    case Right(Ack) => { /* no response message to set */}
    case Right(failure : Failure) => exchange.setFailure(failure)
    case Right(msg) => exchange.setFailure(Failure(new IllegalArgumentException("Expected Ack or Failure message, but got: "+msg)))
    case Left(throwable) =>  exchange.setFailure(Failure(throwable))
  }

  private[this] def either[T](block: => T) : Either[Throwable,T] = try {Right(block)} catch {case e => Left(e)}

  private[this] def actorFor(path:ActorEndpointPath) : ActorRef =
    path.findActorIn(camel.system) getOrElse (throw new ActorNotRegisteredException(path.actorPath))

  private[this] def messageFor(exchange: CamelExchangeAdapter)  =
     exchange.toRequestMessage(Map(Message.MessageExchangeId -> exchange.getExchangeId))

}

/**
 * Thrown to indicate that an actor referenced by an endpoint URI cannot be
 * found in the Actor.registry.
 *
 * @author Martin Krasser
 */
class ActorNotRegisteredException(uri: String) extends RuntimeException{
  override def getMessage = "Actor '%s' doesn't exist" format uri
}


private[camel] object DurationTypeConverter extends CamelTypeConverter {
  def convertTo[T](`type`: Class[T], value: AnyRef) = {
    require(value.toString.endsWith(" nanos"))
    Duration.fromNanos(value.toString.dropRight(6).toLong).asInstanceOf[T]
  }

  def toString(duration: Duration) = duration.toNanos +" nanos"
}

/**
 * Converter required by akka
 */
private[camel] object CommunicationStyleTypeConverter extends CamelTypeConverter{
  import akka.util.duration._
  val blocking = """Blocking\((\d+) nanos\)""".r
  def convertTo[T](`type`: Class[T], value: AnyRef) = `type` match{
    case c if c == classOf[CommunicationStyle] => value.toString match  {
      case blocking(timeout) => Blocking(timeout.toLong nanos).asInstanceOf[T]
      case "NonBlocking" => NonBlocking.asInstanceOf[T]
    }
  }


  def toString(b : CommunicationStyle)  = b match{
    case NonBlocking => NonBlocking.toString
    case Blocking(timeout) => "Blocking(%d nanos)".format(timeout.toNanos)
  }

}

private[camel] abstract class CamelTypeConverter extends TypeConverter{
  def convertTo[T](`type`: Class[T], exchange: Exchange, value: AnyRef) = convertTo(`type`, value)
  def mandatoryConvertTo[T](`type`: Class[T], value: AnyRef) = convertTo(`type`, value)
  def mandatoryConvertTo[T](`type`: Class[T], exchange: Exchange, value: AnyRef) = convertTo(`type`, value)
}


private[camel] case class ActorEndpointPath private(actorPath: String) {
  require(actorPath != null)
  require(actorPath.length() > 0)
  def toCamelPath(config: ConsumerConfig = new ConsumerConfig {}) : String =  "actor://path:%s?%s" format (actorPath, config.toCamelParameters)

  def findActorIn(system: ActorSystem) : Option[ActorRef] = {
    val ref = system.actorFor(actorPath)
    if (ref.isTerminated) None else Some(ref)
  }

}

private[camel] object ActorEndpointPath{
  def apply(actorRef: ActorRef) = new ActorEndpointPath(actorRef.path.toString)

  /**
   * Expects path in a format: path:%s
   */
  def fromCamelPath(camelPath : String) =  camelPath match {
    case id if id startsWith "path:"   => new ActorEndpointPath(id substring 5)
    case _ => throw new IllegalArgumentException("Invalid path: [%s] - should be path:<actorPath>" format camelPath)
  }
}
