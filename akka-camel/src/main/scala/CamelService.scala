/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.camel

import se.scalablesolutions.akka.actor.Actor._
import se.scalablesolutions.akka.actor.{AspectInitRegistry, ActorRegistry}
import se.scalablesolutions.akka.util.{Bootable, Logging}

/**
 * Used by applications (and the Kernel) to publish consumer actors and active objects via
 * Camel endpoints and to manage the life cycle of a a global CamelContext which can be
 * accessed via <code>se.scalablesolutions.akka.camel.CamelContextManager.context</code>.
 *
 * @author Martin Krasser
 */
trait CamelService extends Bootable with Logging {

  import CamelContextManager._

  private[camel] val consumerPublisher = actorOf[ConsumerPublisher]
  private[camel] val publishRequestor =  actorOf[PublishRequestor]

  // add listener for actor registration events
  ActorRegistry.addListener(publishRequestor)

  // add listener for AspectInit registration events
  AspectInitRegistry.addListener(publishRequestor)

  /**
   * Starts the CamelService. Any started actor that is a consumer actor will be (asynchronously)
   * published as Camel endpoint. Consumer actors that are started after this method returned will
   * be published as well. Actor publishing is done asynchronously. A started (loaded) CamelService
   * also publishes <code>@consume</code> annotated methods of active objects that have been created
   * with <code>ActiveObject.newInstance(..)</code> (and <code>ActiveObject.newInstance(..)</code>
   * on a remote node).
   */
  abstract override def onLoad = {
    super.onLoad

    // Only init and start if not already done by application
    if (!initialized) init
    if (!started) start

    // start actor that exposes consumer actors and active objects via Camel endpoints
    consumerPublisher.start

    // init publishRequestor so that buffered and future events are delivered to consumerPublisher
    publishRequestor ! PublishRequestorInit(consumerPublisher)
  }

  /**
   * Stops the CamelService.
   */
  abstract override def onUnload = {
    ActorRegistry.removeListener(publishRequestor)
    AspectInitRegistry.removeListener(publishRequestor)
    consumerPublisher.stop
    stop
    super.onUnload
  }

  /**
   * Starts the CamelService.
   *
   * @see onLoad
   */
  def load: CamelService = {
    onLoad
    this
  }

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
  def newInstance: CamelService = new DefaultCamelService
}

/**
 * Default CamelService implementation to be created in Java applications with
 * <pre>
 * CamelService service = new DefaultCamelService()
 * </pre>
 */
class DefaultCamelService extends CamelService {
}
