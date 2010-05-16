/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.camel.service

import se.scalablesolutions.akka.actor.ActorRegistry
import se.scalablesolutions.akka.actor.Actor._
import se.scalablesolutions.akka.camel.CamelContextManager
import se.scalablesolutions.akka.util.{Bootable, Logging}

/**
 * Used by applications (and the Kernel) to publish consumer actors via Camel
 * endpoints and to manage the life cycle of a a global CamelContext which can
 * be accessed via se.scalablesolutions.akka.camel.CamelContextManager.
 *
 * @author Martin Krasser
 */
trait CamelService extends Bootable with Logging {

  import CamelContextManager._

  private[camel] val consumerPublisher = actorOf[ConsumerPublisher]
  private[camel] val publishRequestor =  actorOf(new PublishRequestor(consumerPublisher))

  /**
   * Starts the CamelService. Any started actor that is a consumer actor will be (asynchronously)
   * published as Camel endpoint. Consumer actors that are started after this method returned will
   * be published as well. Actor publishing is done asynchronously.
   */
  abstract override def onLoad = {
    super.onLoad

    // Only init and start if not already done by application
    if (!initialized) init
    if (!started) start

    // Camel should cache input streams
    context.setStreamCaching(true)

    // start actor that exposes consumer actors via Camel endpoints
    consumerPublisher.start

    // add listener for actor registration events
    ActorRegistry.addRegistrationListener(publishRequestor.start)

    // publish already registered consumer actors
    for (actor <- ActorRegistry.actors; event <- ConsumerRegistered.forConsumer(actor)) consumerPublisher ! event
  }

  /**
   * Stops the CamelService.
   */
  abstract override def onUnload = {
    ActorRegistry.removeRegistrationListener(publishRequestor)
    publishRequestor.stop
    consumerPublisher.stop
    stop
    super.onUnload
  }

  /**
   * Starts the CamelService.
   *
   * @see onLoad
   */
  def load = onLoad

  /**
   * Stops the CamelService.
   *
   * @see onUnload
   */
  def unload = onUnload
}

/**
 * CamelService companion object used by standalone applications to create their own
 * CamelService instance.
 *
 * @author Martin Krasser
 */
object CamelService {

  /**
   * Creates a new CamelService instance.
   */
  def newInstance: CamelService = new CamelService {}
}
