/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import org.apache.camel.CamelContext

import akka.actor.Actor._
import akka.actor._
import akka.camel.component.TypedActorComponent

/**
 * Module that adds typed consumer actor support to akka-camel. It is automatically
 * detected by CamelService if added to the classpath.
 *
 * @author Martin Krasser
 */
private[camel] object TypedCamel {
  private var consumerPublisher: ActorRef = _
  private var publishRequestor: ActorRef = _

  /**
   * Adds the <code>TypedActorComponent</code> to <code>context</code>.
   */
  def onCamelContextInit(context: CamelContext) {
    context.addComponent(TypedActorComponent.InternalSchema, new TypedActorComponent)
  }

  /**
   * Configures a <code>TypedConsumerPublishRequestor</code> and a <code>TypedConsumerPublisher</code>
   * and re-uses the <code>activationTracker</code> of <code>service</code>.
   */
  def onCamelServiceStart(service: CamelService) {
    consumerPublisher = new LocalActorRef(Props(new TypedConsumerPublisher(service.activationTracker)), Props.randomName, true)
    publishRequestor = new LocalActorRef(Props(new TypedConsumerPublishRequestor), Props.randomName, true)

    registerPublishRequestor

    for (event ← PublishRequestor.pastActorRegisteredEvents) publishRequestor ! event
    publishRequestor ! InitPublishRequestor(consumerPublisher)
  }

  /**
   * Stops the configured Configures <code>TypedConsumerPublishRequestor</code> and
   * <code>TypedConsumerPublisher</code>.
   */
  def onCamelServiceStop(service: CamelService) {
    unregisterPublishRequestor
    consumerPublisher.stop
  }

  private def registerPublishRequestor: Unit = registry.addListener(publishRequestor)
  private def unregisterPublishRequestor: Unit = registry.removeListener(publishRequestor)
}
