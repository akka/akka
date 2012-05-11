/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import akka.actor.Actor
import internal.CamelExchangeAdapter
import org.apache.camel.{ Exchange, ExchangePattern, AsyncCallback }
import akka.actor.Status.Failure

/**
 * Support trait for producing messages to Camel endpoints.
 *
 * @author Martin Krasser
 */
trait ProducerSupport { this: Actor ⇒
  protected[this] implicit def camel = CamelExtension(context.system)

  /**
   * camelContext implicit is useful when using advanced methods of CamelMessage.
   */
  protected[this] implicit def camelContext = camel.context

  protected[this] lazy val (endpoint, processor) = camel.registerProducer(self, endpointUri)

  /**
   * CamelMessage headers to copy by default from request message to response-message.
   */
  private val headersToCopyDefault = Set(CamelMessage.MessageExchangeId)

  /**
   * If set to false (default), this producer expects a response message from the Camel endpoint.
   * If set to true, this producer initiates an in-only message exchange with the Camel endpoint
   * (fire and forget).
   */
  def oneway: Boolean = false

  /**
   * Returns the Camel endpoint URI to produce messages to.
   */
  def endpointUri: String

  /**
   * Returns the names of message headers to copy from a request message to a response message.
   * By default only the CamelMessage.MessageExchangeId is copied. Applications may override this to
   * define an application-specific set of message headers to copy.
   */
  def headersToCopy: Set[String] = headersToCopyDefault

  /**
   * Initiates a message exchange of given <code>pattern</code> with the endpoint specified by
   * <code>endpointUri</code>. The in-message of the initiated exchange is the canonical form
   * of <code>msg</code>. After sending the in-message, the processing result (response) is passed
   * as argument to <code>receiveAfterProduce</code>. If the response is received synchronously from
   * the endpoint then <code>receiveAfterProduce</code> is called synchronously as well. If the
   * response is received asynchronously, the <code>receiveAfterProduce</code> is called
   * asynchronously. The original
   * sender and senderFuture are preserved.
   *
   * @see CamelMessage#canonicalize(Any)
   *
   * @param msg message to produce
   * @param pattern exchange pattern
   */
  protected def produce(msg: Any, pattern: ExchangePattern): Unit = {
    implicit def toExchangeAdapter(exchange: Exchange): CamelExchangeAdapter = new CamelExchangeAdapter(exchange)

    val cmsg = CamelMessage.canonicalize(msg)
    val exchange = endpoint.createExchange(pattern)
    exchange.setRequest(cmsg)
    processor.process(exchange, new AsyncCallback {
      val producer = self
      // Need copies of sender reference here since the callback could be done
      // later by another thread.
      val originalSender = sender
      // Ignoring doneSync, sending back async uniformly.
      def done(doneSync: Boolean): Unit = producer.tell(
        if (exchange.isFailed) exchange.toFailureResult(cmsg.headers(headersToCopy))
        else MessageResult(exchange.toResponseMessage(cmsg.headers(headersToCopy))), originalSender)
    })
  }

  /**
   * Produces <code>msg</code> to the endpoint specified by <code>endpointUri</code>. Before the message is
   * actually sent it is pre-processed by calling <code>receiveBeforeProduce</code>. If <code>oneway</code>
   * is <code>true</code>, an in-only message exchange is initiated, otherwise an in-out message exchange.
   *
   * @see Producer#produce(Any, ExchangePattern)
   */
  protected def produce: Receive = {
    case res: MessageResult ⇒ routeResponse(res.message)
    case res: FailureResult ⇒
      val e = new AkkaCamelException(res.cause, res.headers)
      routeResponse(Failure(e))
      throw e
    case msg ⇒
      val exchangePattern = if (oneway) ExchangePattern.InOnly else ExchangePattern.InOut
      produce(transformOutgoingMessage(msg), exchangePattern)
  }

  /**
   * Called before the message is sent to the endpoint specified by <code>endpointUri</code>. The original
   * message is passed as argument. By default, this method simply returns the argument but may be overridden
   * by subtraits or subclasses.
   */
  protected def transformOutgoingMessage(msg: Any): Any = msg

  /**
   * Called before the response message is sent to the original sender. The original
   * message is passed as argument. By default, this method simply returns the argument but may be overridden
   * by subtraits or subclasses.
   */
  protected def transformResponse(msg: Any): Any = msg

  /**
   * Called after a response was received from the endpoint specified by <code>endpointUri</code>. The
   * response is passed as argument. By default, this method sends the response back to the original sender
   * if <code>oneway</code> is <code>false</code>. If <code>oneway</code> is <code>true</code>, nothing is
   * done. This method may be overridden by subtraits or subclasses (e.g. to forward responses to another
   * actor).
   */

  protected def routeResponse(msg: Any): Unit = if (!oneway) sender ! transformResponse(msg)

}

/**
 * Mixed in by Actor implementations to produce messages to Camel endpoints.
 */
trait Producer extends ProducerSupport { this: Actor ⇒

  /**
   * Default implementation of Actor.receive. Any messages received by this actors
   * will be produced to the endpoint specified by <code>endpointUri</code>.
   */
  protected def receive = produce
}

/**
 * @author Martin Krasser
 */
private case class MessageResult(message: CamelMessage)

/**
 * @author Martin Krasser
 */
private case class FailureResult(cause: Throwable, headers: Map[String, Any] = Map.empty)

/**
 * A one-way producer.
 *
 * @author Martin Krasser
 */
trait Oneway extends Producer { this: Actor ⇒
  override def oneway = true
}

