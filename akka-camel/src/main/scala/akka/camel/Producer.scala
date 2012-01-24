/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import org.apache.camel.{ Message => CamelMessage, ExchangePattern, AsyncCallback, Exchange}
import org.apache.camel.processor.SendProcessor

import akka.actor.Actor
import akka.camel.CamelMessageConversion._
/**
 * Support trait for producing messages to Camel endpoints.
 *
 * @author Martin Krasser
 */
trait ProducerSupport { this: Actor =>
  protected [this] val camel = CamelExtension(context.system)

  /**
   * Message headers to copy by default from request message to response-message.
   */
  private val headersToCopyDefault = Set(Message.MessageExchangeId)

  /**
   * <code>Endpoint</code> object resolved from the current CamelContext with
   * <code>endpointUri</code>.
   */
  private lazy val endpoint = camel.context.getEndpoint(endpointUri)

  /**
   * <code>SendProcessor</code> for producing messages to <code>endpoint</code>.
   */
  private lazy val processor ={
    val sendProcessor = new SendProcessor(endpoint)
    sendProcessor.start()
    sendProcessor
  }

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
   * By default only the Message.MessageExchangeId is copied. Applications may override this to
   * define an application-specific set of message headers to copy.
   */
  def headersToCopy: Set[String] = headersToCopyDefault

  /**
   * Default implementation of <code>Actor.preRestart</code> for freeing resources needed
   * to actually send messages to <code>endpointUri</code>.
   */
  override def preRestart(reason: Throwable, message: Option[Any]) {
    try { preRestartProducer(reason) } finally { processor.stop }
  }

  /**
   * Does nothing by default. Can be overridden by concrete producers for implementing a
   * pre-restart callback handler.
   */
  def preRestartProducer(reason: Throwable) {}

  /**
   * Default implementation of <code>Actor.postStop</code> for freeing resources needed
   * to actually send messages to <code>endpointUri</code>.
   */
  override def postStop {
    processor.stop
  }

  /**
   * Initiates a message exchange of given <code>pattern</code> with the endpoint specified by
   * <code>endpointUri</code>. The in-message of the initiated exchange is the canonical form
   * of <code>msg</code>. After sending the in-message, the processing result (response) is passed
   * as argument to <code>receiveAfterProduce</code>. If the response is received synchronously from
   * the endpoint then <code>receiveAfterProduce</code> is called synchronously as well. If the
   * response is received asynchronously, the <code>receiveAfterProduce</code> is called
   * asynchronously. This is done by forwarding the result. The original
   * sender and senderFuture are thereby preserved.
   *
   * @see Message#canonicalize(Any)
   *
   * @param msg message to produce
   * @param pattern exchange pattern
   */
  protected def produce(msg: Any, pattern: ExchangePattern): Unit = {
    val cmsg = Message.canonicalize(msg, camel)
    val exchange = createExchangeFor(pattern).setRequest(cmsg)
    processor.process(exchange, new AsyncCallback {
      val producer = self
      // Need copies of sender reference here since the callback could be done
      // later by another thread.
      //
      val originalSender = sender

      def done(doneSync: Boolean): Unit = {
        (doneSync, exchange.isFailed) match {
          //TODO this boolean logic is smart, but less readable as opposed to traditional if statement.
          case (true, true)   => dispatchSync(exchange.toFailureMessage(cmsg.headers(headersToCopy)))
          case (true, false)  => dispatchSync(exchange.toResponseMessage(cmsg.headers(headersToCopy)))
          case (false, true)  => dispatchAsync(FailureResult(exchange.toFailureMessage(cmsg.headers(headersToCopy))))
          case (false, false) => dispatchAsync(MessageResult(exchange.toResponseMessage(cmsg.headers(headersToCopy))))
        }
      }

      private def dispatchSync(result: Any) = receiveAfterProduce(result)

      private def dispatchAsync(result: Any) = {
        // preserving the originalSender, so that in overriding receiveAfterProduce it is possible to forward responses to another actor
        producer.tell(result,originalSender)
      }
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
   * Called after a response was received from the endpoint specified by <code>endpointUri</code>. The
   * response is passed as argument. By default, this method sends the response back to the original sender
   * if <code>oneway</code> is <code>false</code>. If <code>oneway</code> is <code>true</code>, nothing is
   * done. This method may be overridden by subtraits or subclasses (e.g. to forward responses to another
   * actor).
   */
  protected def receiveAfterProduce: Receive = {
    case msg => if (!oneway) sender ! msg
  }

  /**
   * Creates a new Exchange of given <code>pattern</code> from the endpoint specified by
   * <code>endpointUri</code>.
   */
  private def createExchangeFor(pattern: ExchangePattern): Exchange = endpoint.createExchange(pattern)

}

/**
 * Mixed in by Actor implementations to produce messages to Camel endpoints.
 */
trait Producer extends ProducerSupport { this: Actor =>

  /**
   * Default implementation of Actor.receive. Any messages received by this actors
   * will be produced to the endpoint specified by <code>endpointUri</code>.
   */
  protected def receive = produce
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

