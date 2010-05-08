/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.camel

import CamelMessageConversion.toExchangeAdapter

import org.apache.camel.{Processor, ExchangePattern, Exchange, ProducerTemplate}
import org.apache.camel.impl.DefaultExchange
import org.apache.camel.spi.Synchronization

import se.scalablesolutions.akka.actor.{Actor, ActorRef}
import se.scalablesolutions.akka.dispatch.CompletableFuture
import se.scalablesolutions.akka.util.Logging

/**
 * Mixed in by Actor implementations that produce messages to Camel endpoints.
 *
 * @author Martin Krasser
 */
trait Producer { this: Actor =>

  private val headersToCopyDefault = Set(Message.MessageExchangeId)

  /**
   * If set to true (default), communication with the Camel endpoint is done via the Camel
   * <a href="http://camel.apache.org/async.html">Async API</a>. Camel then processes the
   * message in a separate thread. If set to false, the actor thread is blocked until Camel
   * has finished processing the produced message.
   */
  def async: Boolean = true

  /**
   * If set to false (default), this producer expects a response message from the Camel endpoint.
   * If set to true, this producer communicates with the Camel endpoint with an in-only message
   * exchange pattern (fire and forget).
   */
  def oneway: Boolean = false

  /**
   * Returns the Camel endpoint URI to produce messages to.
   */
  def endpointUri: String

  /**
   * Returns the names of message headers to copy from a request message to a response message.
   * By default only the Message.MessageExchangeId is copied. Applications may override this to
   * define an application-specific set of message headers to copy.
   */
  def headersToCopy: Set[String] = headersToCopyDefault

  /**
   * Returns the producer template from the CamelContextManager. Applications either have to ensure
   * proper initialization of CamelContextManager or override this method.
   *
   * @see CamelContextManager.
   */
  protected def template: ProducerTemplate = CamelContextManager.template

  /**
   * Initiates a one-way (in-only) message exchange to the Camel endpoint given by
   * <code>endpointUri</code>. This method blocks until Camel finishes processing
   * the message exchange.
   *
   * @param msg: the message to produce. The message is converted to its canonical
   *             representation via Message.canonicalize.
   */
  protected def produceOnewaySync(msg: Any): Unit =
    template.send(endpointUri, createInOnlyExchange.fromRequestMessage(Message.canonicalize(msg)))

  /**
   * Initiates a one-way (in-only) message exchange to the Camel endpoint given by
   * <code>endpointUri</code>. This method triggers asynchronous processing of the
   * message exchange by Camel.
   *
   * @param msg: the message to produce. The message is converted to its canonical
   *             representation via Message.canonicalize.
   */
  protected def produceOnewayAsync(msg: Any): Unit =
    template.asyncSend(
      endpointUri, createInOnlyExchange.fromRequestMessage(Message.canonicalize(msg)))

  /**
   * Initiates a two-way (in-out) message exchange to the Camel endpoint given by
   * <code>endpointUri</code>. This method blocks until Camel finishes processing
   * the message exchange.
   *
   * @param msg: the message to produce. The message is converted to its canonical
   *             representation via Message.canonicalize.
   * @return either a response Message or a Failure object.
   */
  protected def produceSync(msg: Any): Any = {
    val cmsg = Message.canonicalize(msg)
    val requestProcessor = new Processor() {
      def process(exchange: Exchange) = exchange.fromRequestMessage(cmsg)
    }
    val result = template.request(endpointUri, requestProcessor)
    if (result.isFailed) result.toFailureMessage(cmsg.headers(headersToCopy))
    else                 result.toResponseMessage(cmsg.headers(headersToCopy))
  }

  /**
   * Initiates a two-way (in-out) message exchange to the Camel endpoint given by
   * <code>endpointUri</code>.  This method triggers asynchronous processing of the
   * message exchange by Camel. The response message is returned asynchronously to
   * the original sender (or sender future).
   *
   * @param msg: the message to produce. The message is converted to its canonical
   *             representation via Message.canonicalize.
   * @return either a response Message or a Failure object.
   * @see ProducerResponseSender
   */
  protected def produceAsync(msg: Any): Unit = {
    val cmsg = Message.canonicalize(msg)
    val sync = new ProducerResponseSender(
      cmsg.headers(headersToCopy), self.replyTo, this)
    template.asyncCallback(endpointUri, createInOutExchange.fromRequestMessage(cmsg), sync)
  }

  /**
   * Default implementation for Actor.receive. Implementors may choose to
   * <code>def receive = produce</code>. This partial function calls one of
   * the protected produce methods depending on the return values of
   * <code>oneway</code> and <code>async</code>.
   */
  protected def produce: PartialFunction[Any, Unit] = {
    case msg => {
      if      ( oneway && !async)    produceOnewaySync(msg)
      else if ( oneway &&  async)    produceOnewayAsync(msg)
      else if (!oneway && !async)    reply(produceSync(msg))
      else  /*(!oneway &&  async)*/  produceAsync(msg)
    }
  }

  /**
   * Creates a new in-only Exchange.
   */
  protected def createInOnlyExchange: Exchange = createExchange(ExchangePattern.InOnly)

  /**
   * Creates a new in-out Exchange.
   */
  protected def createInOutExchange: Exchange = createExchange(ExchangePattern.InOut)

  /**
   * Creates a new Exchange with given pattern from the CamelContext managed by
   * CamelContextManager. Applications either have to ensure proper initialization
   * of CamelContextManager or override this method.
   *
   * @see CamelContextManager.
   */
  protected def createExchange(pattern: ExchangePattern): Exchange =
    new DefaultExchange(CamelContextManager.context, pattern)
}

/**
 * Synchronization object that sends responses asynchronously to initial senders. This
 * class is used by Producer for asynchronous two-way messaging with a Camel endpoint.
 *
 * @author Martin Krasser
 */
class ProducerResponseSender(
    headers: Map[String, Any],
    replyTo : Option[Either[ActorRef, CompletableFuture[Any]]],
    producer: Actor) extends Synchronization with Logging {

  implicit val producerActor = Some(producer) // the response sender

  /**
   * Replies a Failure message, created from the given exchange, to <code>sender</code> (or
   * <code>senderFuture</code> if applicable).
   */
  def onFailure(exchange: Exchange) = reply(exchange.toFailureMessage(headers))

  /**
   * Replies a response Message, created from the given exchange, to <code>sender</code> (or
   * <code>senderFuture</code> if applicable).
   */
  def onComplete(exchange: Exchange) = reply(exchange.toResponseMessage(headers))

  private def reply(message: Any) = replyTo match {
    case Some(Left(actor))   => actor ! message
    case Some(Right(future)) => future.completeWithResult(message)
    case _                   => log.warning("No destination for sending response")
  }
}

/**
 * A one-way producer.
 *
 * @author Martin Krasser
 */
trait Oneway extends Producer { this: Actor =>
  override def oneway = true
}

/**
 * A synchronous producer.
 *
 * @author Martin Krasser
 */
trait Sync extends Producer { this: Actor =>
  override def async = false
}

