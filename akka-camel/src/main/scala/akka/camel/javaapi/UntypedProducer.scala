package akka.camel.javaapi

import akka.actor.UntypedActor
import akka.camel._

/**
 * Java-friendly ProducerSupport.
 *
 * @see UntypedProducerActor
 *
 * @author Martin Krasser
 */
trait UntypedProducer extends ProducerSupport { this: UntypedActor =>
  final override def endpointUri = getEndpointUri
  final override def oneway = isOneway

  final override def receiveBeforeProduce = {
    case msg => onReceiveBeforeProduce(msg)
  }

  final override def receiveAfterProduce = {
    case msg => onReceiveAfterProduce(msg)
  }

  /**
   * Default implementation of UntypedActor.onReceive
   */
  def onReceive(message: Any) = produce(message)

  /**
   * Returns the Camel endpoint URI to produce messages to.
   */
  def getEndpointUri(): String

  /**
   * If set to false (default), this producer expects a response message from the Camel endpoint.
   * If set to true, this producer communicates with the Camel endpoint with an in-only message
   * exchange pattern (fire and forget).
   */
  def isOneway() = super.oneway

  /**
   * Called before the message is sent to the endpoint specified by <code>getEndpointUri</code>. The original
   * message is passed as argument. By default, this method simply returns the argument but may be overridden
   * by subclasses.
   */
  @throws(classOf[Exception])
  def onReceiveBeforeProduce(message: Any): Any = super.receiveBeforeProduce(message)

  /**
   * Called after a response was received from the endpoint specified by <code>endpointUri</code>. The
   * response is passed as argument. By default, this method sends the response back to the original sender
   * if <code>oneway</code> is <code>false</code>. If <code>oneway</code> is <code>true</code>, nothing is
   * done. This method may be overridden by subclasses (e.g. to forward responses to another actor).
   */
  @throws(classOf[Exception])
  def onReceiveAfterProduce(message: Any): Unit = super.receiveAfterProduce(message)
}

/**
 * Subclass this abstract class to create an untyped producer actor. This class is meant to be used from Java.
 *
 * @author Martin Krasser
 */
abstract class UntypedProducerActor extends UntypedActor with UntypedProducer
