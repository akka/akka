/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package se.scalablesolutions.akka.camel

import java.util.concurrent.CountDownLatch

import org.apache.camel.CamelContext

import se.scalablesolutions.akka.actor.Actor._
import se.scalablesolutions.akka.actor.{AspectInitRegistry, ActorRegistry}
import se.scalablesolutions.akka.util.{Bootable, Logging}

/**
 * Used by applications (and the Kernel) to publish consumer actors and typed actors via
 * Camel endpoints and to manage the life cycle of a a global CamelContext which can be
 * accessed via <code>se.scalablesolutions.akka.camel.CamelContextManager.context</code>.
 *
 * @author Martin Krasser
 */
trait CamelService extends Bootable with Logging {
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
   * also publishes <code>@consume</code> annotated methods of typed actors that have been created
   * with <code>TypedActor.newInstance(..)</code> (and <code>TypedActor.newInstance(..)</code>
   * on a remote node).
   */
  abstract override def onLoad = {
    super.onLoad

    // Only init and start if not already done by application
    if (!CamelContextManager.initialized) CamelContextManager.init
    if (!CamelContextManager.started) CamelContextManager.start

    // start actor that exposes consumer actors and typed actors via Camel endpoints
    consumerPublisher.start

    // init publishRequestor so that buffered and future events are delivered to consumerPublisher
    publishRequestor ! PublishRequestorInit(consumerPublisher)

    // Register this instance as current CamelService
    CamelServiceManager.register(this)
  }

  /**
   * Stops the CamelService.
   */
  abstract override def onUnload = {
    // Unregister this instance as current CamelService
    CamelServiceManager.unregister(this)

    // Remove related listeners from registry
    ActorRegistry.removeListener(publishRequestor)
    AspectInitRegistry.removeListener(publishRequestor)

    // Stop related services
    consumerPublisher.stop
    CamelContextManager.stop

    super.onUnload
  }

  @deprecated("use start() instead")
  def load: CamelService = {
    onLoad
    this
  }

  @deprecated("use stop() instead")
  def unload = onUnload

  /**
   * Starts the CamelService.
   *
   * @see onLoad
   */
  def start: CamelService = {
    onLoad
    this
  }

  /**
   * Stops the CamelService.
   *
   * @see onUnload
   */
  def stop = onUnload

  /**
   * Sets an expectation of the number of upcoming endpoint activations and returns
   * a {@link CountDownLatch} that can be used to wait for the activations to occur.
   * Endpoint activations that occurred in the past are not considered.
   */
  def expectEndpointActivationCount(count: Int): CountDownLatch =
    (consumerPublisher !! SetExpectedRegistrationCount(count)).as[CountDownLatch].get

  /**
   * Sets an expectation of the number of upcoming endpoint de-activations and returns
   * a {@link CountDownLatch} that can be used to wait for the de-activations to occur.
   * Endpoint de-activations that occurred in the past are not considered.
   */
  def expectEndpointDeactivationCount(count: Int): CountDownLatch =
    (consumerPublisher !! SetExpectedUnregistrationCount(count)).as[CountDownLatch].get
}

/**
 * ...
 *
 * @author Martin Krasser
 */
object CamelServiceManager {

  /**
   * The current (optional) CamelService. Is defined when a CamelService has been started.
   */
  private var _current: Option[CamelService] = None

  /**
   * Starts a new CamelService and makes it the current CamelService.
   */
  def startCamelService = CamelServiceFactory.createCamelService.start

  /**
   * Stops the current CamelService.
   */
  def stopCamelService = service.stop

  /**
   * Returns the current CamelService.
   *
   * @throws IllegalStateException if there's no current CamelService.
   */
  def service =
    if (_current.isDefined) _current.get
    else throw new IllegalStateException("no current CamelService")

  private[camel] def register(service: CamelService) =
    if (_current.isDefined) throw new IllegalStateException("current CamelService already registered")
    else _current = Some(service)

  private[camel] def unregister(service: CamelService) =
    if (_current == Some(service)) _current = None
    else throw new IllegalStateException("only current CamelService can be unregistered")
}

/**
 * @author Martin Krasser
 */
object CamelServiceFactory {
  /**
   * Creates a new CamelService instance
   */
  def createCamelService: CamelService = new CamelService { }

  /**
   * Creates a new CamelService instance
   */
  def createCamelService(camelContext: CamelContext): CamelService = {
    CamelContextManager.init(camelContext)
    createCamelService
  }
}
