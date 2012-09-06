/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import akka.actor.{ ActorRef, Actor }
import internal.CamelExchangeAdapter
import akka.actor.Status.Failure
import org.apache.camel.{ Endpoint, ExchangePattern, AsyncCallback }
import org.apache.camel.processor.SendProcessor
import akka.camel.internal.Register

/**
 * Support trait for producing messages to Camel endpoints.
 *
 * @author Martin Krasser
 */
trait ProducerSupport extends Actor with CamelSupport {
  private var messages: Map[ActorRef, Any] = Map[ActorRef, Any]()
  private var _endpoint: Option[Endpoint] = None
  private var _processor: Option[SendProcessor] = None

  override def preStart() {
    super.preStart()
    camel.supervisor ! Register(self, endpointUri)
  }

  /**
   * CamelMessage headers to copy by default from request message to response-message.
   */
  private val headersToCopyDefault: Set[String] = Set(CamelMessage.MessageExchangeId)

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
   * @param endpoint the endpoint
   * @param processor the processor
   * @param msg message to produce
   * @param pattern exchange pattern
   */
  protected def produce(endpoint: Endpoint, processor: SendProcessor, msg: Any, pattern: ExchangePattern): Unit = {
    // Need copies of sender reference here since the callback could be done
    // later by another thread.
    val producer = self
    val originalSender = sender

    val cmsg = CamelMessage.canonicalize(msg)
    val xchg = new CamelExchangeAdapter(endpoint.createExchange(pattern))

    xchg.setRequest(cmsg)

    processor.process(xchg.exchange, new AsyncCallback {
      // Ignoring doneSync, sending back async uniformly.
      def done(doneSync: Boolean): Unit = producer.tell(
        if (xchg.exchange.isFailed) xchg.toFailureResult(cmsg.headers(headersToCopy))
        else MessageResult(xchg.toResponseMessage(cmsg.headers(headersToCopy))), originalSender)
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
    case (e: Endpoint, p: SendProcessor) ⇒
      _endpoint = Some(e)
      _processor = Some(p)
      messages.foreach { case (sender, msg) ⇒ self.tell(msg, sender) }
      messages = Map()
    case res: MessageResult ⇒ routeResponse(res.message)
    case res: FailureResult ⇒
      val e = new AkkaCamelException(res.cause, res.headers)
      routeResponse(Failure(e))
      throw e
    case msg ⇒
      if (_endpoint.isEmpty || _processor.isEmpty) {
        messages = messages + (sender -> msg)
      } else {
        _endpoint.foreach { endpoint ⇒
          _processor.foreach { processor ⇒
            produce(endpoint, processor, transformOutgoingMessage(msg), if (oneway) ExchangePattern.InOnly else ExchangePattern.InOut)
          }
        }
      }
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
   * Implementation of Actor.receive. Any messages received by this actor
   * will be produced to the endpoint specified by <code>endpointUri</code>.
   */
  final def receive: Actor.Receive = produce
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
  override def oneway: Boolean = true
}

