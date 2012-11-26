/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import akka.actor.{ Props, NoSerializationVerificationNeeded, ActorRef, Actor }
import internal.CamelSupervisor.{ CamelProducerObjects, Register }
import internal.CamelExchangeAdapter
import akka.actor.Status.Failure
import org.apache.camel.{ Endpoint, ExchangePattern, AsyncCallback }
import org.apache.camel.processor.SendProcessor

/**
 * Support trait for producing messages to Camel endpoints.
 */
trait ProducerSupport extends Actor with CamelSupport {
  private[this] var messages = Map[ActorRef, Any]()
  private[this] var producerChild: Option[ActorRef] = None

  override def preStart() {
    super.preStart()
    register()
  }

  private[this] def register() { camel.supervisor ! Register(self, endpointUri) }

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
   * Produces <code>msg</code> to the endpoint specified by <code>endpointUri</code>. Before the message is
   * actually sent it is pre-processed by calling <code>receiveBeforeProduce</code>. If <code>oneway</code>
   * is <code>true</code>, an in-only message exchange is initiated, otherwise an in-out message exchange.
   *
   * @see Producer#produce(Any, ExchangePattern)
   */
  protected def produce: Receive = {
    case CamelProducerObjects(endpoint, processor) ⇒
      if (producerChild.isEmpty) {
        producerChild = Some(context.actorOf(Props(new ProducerChild(endpoint, processor))))
        messages = {
          for (
            child ← producerChild;
            (sender, msg) ← messages
          ) child.tell(msg, sender)
          Map()
        }
      }
    case res: MessageResult ⇒ routeResponse(res.message)
    case res: FailureResult ⇒
      val e = new AkkaCamelException(res.cause, res.headers)
      routeResponse(Failure(e))
      throw e

    case msg ⇒
      producerChild match {
        case Some(child) ⇒ child forward msg
        case None        ⇒ messages += (sender -> msg)
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

  private class ProducerChild(endpoint: Endpoint, processor: SendProcessor) extends Actor {
    def receive = {
      case msg @ (_: FailureResult | _: MessageResult) ⇒ context.parent forward msg
      case msg                                         ⇒ produce(endpoint, processor, transformOutgoingMessage(msg), if (oneway) ExchangePattern.InOnly else ExchangePattern.InOut)
    }
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
      val xchg = new CamelExchangeAdapter(endpoint.createExchange(pattern))
      val cmsg = CamelMessage.canonicalize(msg)
      xchg.setRequest(cmsg)

      processor.process(xchg.exchange, new AsyncCallback {
        // Ignoring doneSync, sending back async uniformly.
        def done(doneSync: Boolean): Unit = producer.tell(
          if (xchg.exchange.isFailed) xchg.toFailureResult(cmsg.headers(headersToCopy))
          else MessageResult(xchg.toResponseMessage(cmsg.headers(headersToCopy))), originalSender)
      })
    }
  }
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
 * For internal use only.
 *
 */
private case class MessageResult(message: CamelMessage) extends NoSerializationVerificationNeeded

/**
 * For internal use only.
 *
 */
private case class FailureResult(cause: Throwable, headers: Map[String, Any] = Map.empty) extends NoSerializationVerificationNeeded

/**
 * A one-way producer.
 *
 *
 */
trait Oneway extends Producer { this: Actor ⇒
  override def oneway: Boolean = true
}

