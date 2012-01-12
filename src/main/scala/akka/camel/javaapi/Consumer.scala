package akka.camel.javaapi

import akka.util.Duration
import org.apache.camel.model.{ProcessorDefinition, RouteDefinition}
import akka.actor.UntypedActor
import akka.camel._

object ConsumerConfigBuilder{
  lazy val default = ConsumerConfig()
}

//TODO I hate this config builder - there must be an easier way...
class ConsumerConfigBuilder{
  import ConsumerConfigBuilder.default
  var activationTimeout: Duration = default.activationTimeout
  var outTimeout : Duration = default.outTimeout
  var blocking : BlockingOrNot = default.blocking
  var autoack : Boolean = default.autoack

  def withActivationTimeout(t:Duration) = {activationTimeout = t; this}
  def withOutTimeout(t:Duration) = {outTimeout = t; this}
  def withBlocking(b:BlockingOrNot) = {blocking = b; this}
  def withAutoAck(a:Boolean) = {autoack = a; this}

  def onRouteDefinition(rd: RouteDefinition) : ProcessorDefinition[_] = default.onRouteDefinition(rd)

  def build : ConsumerConfig = new ConsumerConfig(activationTimeout, outTimeout, blocking, autoack){
    override def onRouteDefinition(rd: RouteDefinition) : ProcessorDefinition[_] = ConsumerConfigBuilder.this.onRouteDefinition(rd)
  }
}


/**
 *  Java-friendly Consumer.
 *
 * @see UntypedConsumerActor
 * @see RemoteUntypedConsumerActor
 *
 * @author Martin Krasser
 */
trait UntypedConsumer extends Consumer { self: UntypedActor =>
  final def endpointUri = getEndpointUri

  /**
   * Returns the Camel endpoint URI to consume messages from.
   */
  def getEndpointUri(): String

}

/**
 * Subclass this abstract class to create an MDB-style untyped consumer actor. This
 * class is meant to be used from Java.
 */
abstract class UntypedConsumerActor extends UntypedActor with UntypedConsumer
