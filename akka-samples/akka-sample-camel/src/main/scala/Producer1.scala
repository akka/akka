package sample.camel

import se.scalablesolutions.akka.actor.Actor
import se.scalablesolutions.akka.camel.Producer

/**
 * @author Martin Krasser
 */
class Producer1 extends Actor with Producer {

  def endpointUri = "direct:welcome"

  override def oneway = false // default
  override def async = true   // default

  protected def receive = produce
}