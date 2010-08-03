/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.camel

import CamelMessageConversion.toExchangeAdapter

import org.apache.camel._
import org.apache.camel.processor.SendProcessor

import se.scalablesolutions.akka.actor.{Actor, ActorRef}

/**
 * Mixed in by Actor implementations that produce messages to Camel endpoints.
 *
 * @author Martin Krasser
 */
trait Producer { this: Actor =>

  /**
   * Message headers to copy by default from request message to response-message.
   */
  private val headersToCopyDefault = Set(Message.MessageExchangeId)

  /**
   * <code>Endpoint</code> object resolved from current CamelContext with
   * <code>endpointUri</code>.
   */
  private lazy val endpoint = CamelContextManager.context.getEndpoint(endpointUri)

  /**
   * <code>SendProcessor</code> for producing messages to <code>endpoint</code>.
   */
  private lazy val processor = createSendProcessor

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
   * Default implementation of <code>Actor.shutdown</code> for freeing resources needed
   * to actually send messages to <code>endpointUri</code>.
   */
  override def shutdown {
    processor.stop
  }

  /**
   * Produces <code>msg</code> as exchange of given <code>pattern</code> to the endpoint specified by
   * <code>endpointUri</code>. After producing to the endpoint the processing result is passed as argument
   * to <code>receiveAfterProduce</code>. If the result was returned synchronously by the endpoint then
   * <code>receiveAfterProduce</code> is called synchronously as well. If the result was returned asynchronously,
   * the <code>receiveAfterProduce</code> is called asynchronously as well. This is done by wrapping the result,
   * adding it to this producers mailbox, unwrapping it once it is received and calling
   * <code>receiveAfterProduce</code>. The original sender and senderFuture are thereby preserved.
   *
   * @param msg message to produce
   * @param pattern exchange pattern
   */
  protected def produce(msg: Any, pattern: ExchangePattern): Unit = {
    val cmsg = Message.canonicalize(msg)
    val exchange = createExchange(pattern).fromRequestMessage(cmsg)
    processor.process(exchange, new AsyncCallback {
      val producer = self
      // Need copies of sender and senderFuture references here
      // since the callback could be done later by another thread.
      val sender = self.sender
      val senderFuture = self.senderFuture

      def done(doneSync: Boolean): Unit = {
        (doneSync, exchange.isFailed) match {
          case (true, true)   => dispatchSync(exchange.toFailureMessage(cmsg.headers(headersToCopy)))
          case (true, false)  => dispatchSync(exchange.toResponseMessage(cmsg.headers(headersToCopy)))
          case (false, true)  => dispatchAsync(FailureResult(exchange.toFailureMessage(cmsg.headers(headersToCopy))))
          case (false, false) => dispatchAsync(MessageResult(exchange.toResponseMessage(cmsg.headers(headersToCopy))))
        }
      }

      private def dispatchSync(result: Any) =
        receiveAfterProduce(result)

      private def dispatchAsync(result: Any) = {
        if (senderFuture.isDefined)
          producer.postMessageToMailboxAndCreateFutureResultWithTimeout(result, producer.timeout, sender, senderFuture)
        else
          producer.postMessageToMailbox(result, sender)
      }
    })
  }

  /**
   * Produces <code>msg</code> to the endpoint specified by <code>endpointUri</code>. Before the message is
   * actually produced it is pre-processed by calling <code>receiveBeforeProduce</code>. If <code>oneway</code>
   * is true an in-only message exchange is initiated, otherwise an in-out message exchange.
   *
   * @see Producer#produce(Any, ExchangePattern)
   */
  protected def produce: Receive = {
    case res: MessageResult => receiveAfterProduce(res.message)
    case res: FailureResult => receiveAfterProduce(res.failure)
    case msg => {
      if (oneway)
        produce(receiveBeforeProduce(msg), ExchangePattern.InOnly)
      else
        produce(receiveBeforeProduce(msg), ExchangePattern.InOut)
    }
  }

  /**
   * Called before the message is sent to the endpoint specified by <code>endpointUri</code>. The original
   * message is passed as argument. By default, this method simply returns the argument but may be overridden
   * by subtraits or subclasses.
   */
  protected def receiveBeforeProduce: PartialFunction[Any, Any] = {
    case msg => msg
  }

  /**
   * Called after the a result was received from the endpoint specified by <code>endpointUri</code>. The
   * result is passed as argument. By default, this method replies the result back to the original sender
   * if <code>oneway</code> is false. If <code>oneway</code> is true then nothing is done. This method may
   * be overridden by subtraits or subclasses.
   */
  protected def receiveAfterProduce: Receive = {
    case msg => if (!oneway) self.reply(msg)
  }

  /**
   * Default implementation of Actor.receive
   */
  protected def receive = produce

  /**
   * Creates a new Exchange with given <code>pattern</code> from the endpoint specified by
   * <code>endpointUri</code>.
   */
  private def createExchange(pattern: ExchangePattern): Exchange = endpoint.createExchange(pattern)

  /**
   * Creates a new <code>SendProcessor</code> for <code>endpoint</code>.
   */
  private def createSendProcessor = {
    val sendProcessor = new SendProcessor(endpoint)
    sendProcessor.start
    sendProcessor
  }
}

/**
 * @author Martin Krasser
 */
private[camel] case class MessageResult(message: Message)

/**
 * @author Martin Krasser
 */
private[camel] case class FailureResult(failure: Failure)

/**
 * A one-way producer.
 *
 * @author Martin Krasser
 */
trait Oneway extends Producer { this: Actor =>
  override def oneway = true
}

