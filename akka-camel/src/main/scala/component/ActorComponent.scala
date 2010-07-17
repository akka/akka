/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.camel.component

import java.net.InetSocketAddress
import java.util.{Map => JavaMap}
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

import jsr166x.Deque

import org.apache.camel._
import org.apache.camel.impl.{DefaultProducer, DefaultEndpoint, DefaultComponent}

import se.scalablesolutions.akka.actor.{ActorRegistry, Actor, ActorRef}
import se.scalablesolutions.akka.camel.{Failure, CamelMessageConversion, Message}
import se.scalablesolutions.akka.dispatch.{CompletableFuture, MessageInvocation, MessageDispatcher}
import se.scalablesolutions.akka.stm.TransactionConfig

import scala.reflect.BeanProperty

import CamelMessageConversion.toExchangeAdapter
import java.lang.Throwable

/**
 * Camel component for sending messages to and receiving replies from actors.
 *
 * @see se.scalablesolutions.akka.camel.component.ActorEndpoint
 * @see se.scalablesolutions.akka.camel.component.ActorProducer
 *
 * @author Martin Krasser
 */
class ActorComponent extends DefaultComponent {
  def createEndpoint(uri: String, remaining: String, parameters: JavaMap[String, Object]): ActorEndpoint = {
    val idAndUuid = idAndUuidPair(remaining)
    new ActorEndpoint(uri, this, idAndUuid._1, idAndUuid._2)
  }

  private def idAndUuidPair(remaining: String): Tuple2[Option[String], Option[String]] = {
    remaining split ":" toList match {
      case             id :: Nil => (Some(id), None)
      case   "id" ::   id :: Nil => (Some(id), None)
      case "uuid" :: uuid :: Nil => (None, Some(uuid))
      case _ => throw new IllegalArgumentException(
        "invalid path format: %s - should be <actorid> or id:<actorid> or uuid:<actoruuid>" format remaining)
    }
  }
}

/**
 * Camel endpoint for referencing an actor. The actor reference is given by the endpoint URI.
 * An actor can be referenced by its <code>ActorRef.id</code> or its <code>ActorRef.uuid</code>.
 * Supported endpoint URI formats are
 * <code>actor:&lt;actorid&gt;</code>,
 * <code>actor:id:&lt;actorid&gt;</code> and
 * <code>actor:uuid:&lt;actoruuid&gt;</code>.
 *
 * @see se.scalablesolutions.akka.camel.component.ActorComponent
 * @see se.scalablesolutions.akka.camel.component.ActorProducer

 * @author Martin Krasser
 */
class ActorEndpoint(uri: String,
                    comp: ActorComponent,
                    val id: Option[String],
                    val uuid: Option[String]) extends DefaultEndpoint(uri, comp) {

  /**
   * Blocking of client thread during two-way message exchanges with consumer actors. This is set
   * via the <code>blocking=true|false</code> endpoint URI parameter. If omitted blocking is false.
   */
  @BeanProperty var blocking: Boolean = false

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
 * Sends the in-message of an exchange to an actor. If the exchange pattern is out-capable and
 * <code>blocking</code> is enabled then the producer waits for a reply (using the !! operator),
 * otherwise the ! operator is used for sending the message.
 *
 * @see se.scalablesolutions.akka.camel.component.ActorComponent
 * @see se.scalablesolutions.akka.camel.component.ActorEndpoint
 *
 * @author Martin Krasser
 */
class ActorProducer(val ep: ActorEndpoint) extends DefaultProducer(ep) with AsyncProcessor {
  import ActorProducer._

  def process(exchange: Exchange) =
    if (exchange.getPattern.isOutCapable) sendSync(exchange) else sendAsync(exchange)

  def process(exchange: Exchange, callback: AsyncCallback): Boolean = {
    (exchange.getPattern.isOutCapable, ep.blocking) match {
      case (true, true) => {
        sendSync(exchange)
        callback.done(true)
        true
      }
      case (true, false) => {
        sendAsync(exchange, Some(AsyncCallbackAdapter(exchange, callback)))
        false
      }
      case (false, _) => {
        sendAsync(exchange)
        callback.done(true)
        true
      }
    }
  }

  private def sendSync(exchange: Exchange) = {
    val actor = target
    val result: Any = actor !! requestFor(exchange)

    result match {
      case Some(msg: Failure) => exchange.fromFailureMessage(msg)
      case Some(msg)          => exchange.fromResponseMessage(Message.canonicalize(msg))
      case None               => {
        throw new TimeoutException("timeout (%d ms) while waiting response from %s"
            format (actor.timeout, ep.getEndpointUri))
      }
    }
  }

  private def sendAsync(exchange: Exchange, sender: Option[ActorRef] = None) =
    target.!(requestFor(exchange))(sender)

  private def target =
    targetOption getOrElse (throw new ActorNotRegisteredException(ep.getEndpointUri))

  private def targetOption: Option[ActorRef] =
    if (ep.id.isDefined) targetById(ep.id.get)
    else targetByUuid(ep.uuid.get)

  private def targetById(id: String) = ActorRegistry.actorsFor(id) match {
    case Nil          => None
    case actor :: Nil => Some(actor)
    case actors       => Some(actors.head)
  }

  private def targetByUuid(uuid: String) = ActorRegistry.actorFor(uuid)
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
 * found in the ActorRegistry.
 *
 * @author Martin Krasser
 */
class ActorNotRegisteredException(uri: String) extends RuntimeException {
  override def getMessage = "%s not registered" format uri
}

/**
 * @author Martin Krasser
 */
private[akka] object AsyncCallbackAdapter {
  /**
   * Creates and starts an <code>AsyncCallbackAdapter</code>.
   *
   * @param exchange message exchange to write results to.
   * @param callback callback object to generate completion notifications.
   */
  def apply(exchange: Exchange, callback: AsyncCallback) =
    new AsyncCallbackAdapter(exchange, callback).start
}

/**
 * Adapts an <code>AsyncCallback</code> to <code>ActorRef.!</code>. Used by other actors to reply
 * asynchronously to Camel with <code>ActorRef.reply</code>.
 * <p>
 * <em>Please note</em> that this adapter can only be used locally at the moment which should not
 * be a problem is most situations as Camel endpoints are only activated for local actor references,
 * never for remote references.
 *
 * @author Martin Krasser
 */
private[akka] class AsyncCallbackAdapter(exchange: Exchange, callback: AsyncCallback) extends ActorRef {

  def start = {
    _isRunning = true
    this
  }

  def stop() = {
    _isRunning = false
    _isShutDown = true
  }

  /**
   * Writes the reply <code>message</code> to <code>exchange</code> and uses <code>callback</code> to
   * generate completion notifications.
   *
   * @param message reply message
   * @param sender ignored
   */
  protected[akka] def postMessageToMailbox(message: Any, senderOption: Option[ActorRef]) = {
    message match {
      case msg: Failure => exchange.fromFailureMessage(msg)
      case msg          => exchange.fromResponseMessage(Message.canonicalize(msg))
    }
    callback.done(false)
  }

  def actorClass: Class[_ <: Actor] = unsupported
  def actorClassName = unsupported
  def dispatcher_=(md: MessageDispatcher): Unit = unsupported
  def dispatcher: MessageDispatcher = unsupported
  def transactionConfig_=(config: TransactionConfig): Unit = unsupported
  def transactionConfig: TransactionConfig = unsupported
  def makeTransactionRequired: Unit = unsupported
  def makeRemote(hostname: String, port: Int): Unit = unsupported
  def makeRemote(address: InetSocketAddress): Unit = unsupported
  def homeAddress_=(address: InetSocketAddress): Unit = unsupported
  def remoteAddress: Option[InetSocketAddress] = unsupported
  def link(actorRef: ActorRef): Unit = unsupported
  def unlink(actorRef: ActorRef): Unit = unsupported
  def startLink(actorRef: ActorRef): Unit = unsupported
  def startLinkRemote(actorRef: ActorRef, hostname: String, port: Int): Unit = unsupported
  def spawn[T <: Actor : Manifest]: ActorRef = unsupported
  def spawnRemote[T <: Actor: Manifest](hostname: String, port: Int): ActorRef = unsupported
  def spawnLink[T <: Actor: Manifest]: ActorRef = unsupported
  def spawnLinkRemote[T <: Actor : Manifest](hostname: String, port: Int): ActorRef = unsupported
  def shutdownLinkedActors: Unit = unsupported
  def mailboxSize: Int = unsupported
  def supervisor: Option[ActorRef] = unsupported
  protected[akka] def postMessageToMailboxAndCreateFutureResultWithTimeout[T](message: Any, timeout: Long, senderOption: Option[ActorRef], senderFuture: Option[CompletableFuture[T]]) = unsupported
  protected[akka] def mailbox: Deque[MessageInvocation] = unsupported
  protected[akka] def restart(reason: Throwable, maxNrOfRetries: Int, withinTimeRange: Int): Unit = unsupported
  protected[akka] def restartLinkedActors(reason: Throwable, maxNrOfRetries: Int, withinTimeRange: Int): Unit = unsupported
  protected[akka] def handleTrapExit(dead: ActorRef, reason: Throwable): Unit = unsupported
  protected[akka] def linkedActors: JavaMap[String, ActorRef] = unsupported
  protected[akka] def linkedActorsAsList: List[ActorRef] = unsupported
  protected[akka] def invoke(messageHandle: MessageInvocation): Unit = unsupported
  protected[akka] def remoteAddress_=(addr: Option[InetSocketAddress]): Unit = unsupported
  protected[akka] def registerSupervisorAsRemoteActor = unsupported
  protected[akka] def supervisor_=(sup: Option[ActorRef]): Unit = unsupported
  protected[this] def actorInstance: AtomicReference[Actor] = unsupported

  private def unsupported = throw new UnsupportedOperationException("Not supported for %s" format classOf[AsyncCallbackAdapter].getName)
}