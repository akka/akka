/**
 * Copyright (C) 2009-2010 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.camel

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import org.apache.camel.CamelContext

import akka.actor.{ newUuid, Props, LocalActorRef, Actor }
import akka.config.Config._
import akka.japi.{ SideEffect, Option ⇒ JOption }
import akka.util.Bootable

import TypedCamelAccess._

/**
 * Publishes consumer actors at their Camel endpoints. Consumer actors are published asynchronously when
 * they are started and un-published asynchronously when they are stopped. The CamelService is notified
 * about actor life cycle by registering listeners at Actor.registry.
 * <p>
 * If the akka-camel-typed jar is on the classpath, it is automatically detected by the CamelService. The
 * optional akka-camel-typed jar provides support for typed consumer actors.
 *
 * @author Martin Krasser
 */
trait CamelService extends Bootable {
  private[camel] val activationTracker = new LocalActorRef(Props[ActivationTracker], Props.randomAddress, true)
  private[camel] val consumerPublisher = new LocalActorRef(Props(new ConsumerPublisher(activationTracker)), Props.randomAddress, true)
  private[camel] val publishRequestor = new LocalActorRef(Props(new ConsumerPublishRequestor), Props.randomAddress, true)

  private val serviceEnabled = config.getList("akka.enabled-modules").exists(_ == "camel")

  /**
   * Starts this CamelService if the <code>akka.enabled-modules</code> list contains <code>"camel"</code>.
   */
  abstract override def onLoad = {
    super.onLoad
    if (serviceEnabled) start
  }

  /**
   * Stops this CamelService if the <code>akka.enabled-modules</code> list contains <code>"camel"</code>.
   */
  abstract override def onUnload = {
    if (serviceEnabled) stop
    super.onUnload
  }

  @deprecated("use start() instead", "1.1")
  def load = start

  @deprecated("use stop() instead", "1.1")
  def unload = stop

  /**
   * Starts this CamelService.
   */
  def start: CamelService = {
    // Only init and start if not already done by app
    if (!CamelContextManager.initialized) CamelContextManager.init
    if (!CamelContextManager.started) CamelContextManager.start

    registerPublishRequestor

    // send registration events for all (un)typed actors that have been registered in the past.
    for (event ← PublishRequestor.pastActorRegisteredEvents) publishRequestor ! event

    // init publishRequestor so that buffered and future events are delivered to consumerPublisher
    publishRequestor ! InitPublishRequestor(consumerPublisher)

    for (tc ← TypedCamelModule.typedCamelObject) tc.onCamelServiceStart(this)

    // Register this instance as current CamelService and return it
    CamelServiceManager.register(this)
    CamelServiceManager.mandatoryService
  }

  /**
   * Stops this CamelService.
   */
  def stop = {
    // Unregister this instance as current CamelService
    CamelServiceManager.unregister(this)

    for (tc ← TypedCamelModule.typedCamelObject) tc.onCamelServiceStop(this)

    // Remove related listeners from registry
    unregisterPublishRequestor

    // Stop related services
    consumerPublisher.stop
    activationTracker.stop
    CamelContextManager.stop
  }

  /**
   * Waits for an expected number (<code>count</code>) of consumer actor endpoints to be activated
   * during execution of <code>f</code>. The wait-timeout is by default 10 seconds. Other timeout
   * values can be set via the <code>timeout</code> and <code>timeUnit</code> parameters.
   */
  def awaitEndpointActivation(count: Int, timeout: Long = 10, timeUnit: TimeUnit = TimeUnit.SECONDS)(f: ⇒ Unit): Boolean = {
    val activation = expectEndpointActivationCount(count)
    f; activation.await(timeout, timeUnit)
  }

  /**
   * Waits for an expected number (<code>count</code>) of consumer actor endpoints to be de-activated
   * during execution of <code>f</code>. The wait-timeout is by default 10 seconds. Other timeout
   * values can be set via the <code>timeout</code> and <code>timeUnit</code>
   * parameters.
   */
  def awaitEndpointDeactivation(count: Int, timeout: Long = 10, timeUnit: TimeUnit = TimeUnit.SECONDS)(f: ⇒ Unit): Boolean = {
    val activation = expectEndpointDeactivationCount(count)
    f; activation.await(timeout, timeUnit)
  }

  /**
   * Waits for an expected number (<code>count</code>) of consumer actor endpoints to be activated
   * during execution of <code>p</code>. The wait timeout is 10 seconds.
   * <p>
   * Java API
   */
  def awaitEndpointActivation(count: Int, p: SideEffect): Boolean = {
    awaitEndpointActivation(count, 10, TimeUnit.SECONDS, p)
  }

  /**
   * Waits for an expected number (<code>count</code>) of consumer actor endpoints to be activated
   * during execution of <code>p</code>. Timeout values can be set via the
   * <code>timeout</code> and <code>timeUnit</code> parameters.
   * <p>
   * Java API
   */
  def awaitEndpointActivation(count: Int, timeout: Long, timeUnit: TimeUnit, p: SideEffect): Boolean = {
    awaitEndpointActivation(count, timeout, timeUnit) { p.apply }
  }

  /**
   * Waits for an expected number (<code>count</code>) of consumer actor endpoints to be de-activated
   * during execution of <code>p</code>. The wait timeout is 10 seconds.
   * <p>
   * Java API
   */
  def awaitEndpointDeactivation(count: Int, p: SideEffect): Boolean = {
    awaitEndpointDeactivation(count, 10, TimeUnit.SECONDS, p)
  }

  /**
   * Waits for an expected number (<code>count</code>) of consumer actor endpoints to be de-activated
   * during execution of <code>p</code>. Timeout values can be set via the
   * <code>timeout</code> and <code>timeUnit</code> parameters.
   * <p>
   * Java API
   */
  def awaitEndpointDeactivation(count: Int, timeout: Long, timeUnit: TimeUnit, p: SideEffect): Boolean = {
    awaitEndpointDeactivation(count, timeout, timeUnit) { p.apply }
  }

  /**
   * Sets an expectation on the number of upcoming endpoint activations and returns
   * a CountDownLatch that can be used to wait for the activations to occur. Endpoint
   * activations that occurred in the past are not considered.
   */
  private def expectEndpointActivationCount(count: Int): CountDownLatch =
    (activationTracker ? SetExpectedActivationCount(count)).as[CountDownLatch].get

  /**
   * Sets an expectation on the number of upcoming endpoint de-activations and returns
   * a CountDownLatch that can be used to wait for the de-activations to occur. Endpoint
   * de-activations that occurred in the past are not considered.
   */
  private def expectEndpointDeactivationCount(count: Int): CountDownLatch =
    (activationTracker ? SetExpectedDeactivationCount(count)).as[CountDownLatch].get

  private[camel] def registerPublishRequestor: Unit =
    Actor.registry.addListener(publishRequestor)

  private[camel] def unregisterPublishRequestor: Unit =
    Actor.registry.removeListener(publishRequestor)
}

/**
 * Manages a CamelService (the 'current' CamelService).
 *
 * @author Martin Krasser
 */
object CamelServiceManager {

  /**
   * The current CamelService which is defined when a CamelService has been started.
   */
  private var _current: Option[CamelService] = None

  /**
   * Starts a new CamelService, makes it the current CamelService and returns it.
   *
   * @see CamelService#start
   * @see CamelService#onLoad
   */
  def startCamelService = CamelServiceFactory.createCamelService.start

  /**
   * Stops the current CamelService (if defined).
   *
   * @see CamelService#stop
   * @see CamelService#onUnload
   */
  def stopCamelService = for (s ← service) s.stop

  /**
   * Returns <code>Some(CamelService)</code> if this <code>CamelService</code>
   * has been started, <code>None</code> otherwise.
   */
  def service = _current

  /**
   * Returns the current <code>CamelService</code> if <code>CamelService</code>
   * has been started, otherwise throws an <code>IllegalStateException</code>.
   * <p>
   * Java API
   */
  def getService: JOption[CamelService] = CamelServiceManager.service

  /**
   * Returns <code>Some(CamelService)</code> (containing the current CamelService)
   * if this <code>CamelService</code>has been started, <code>None</code> otherwise.
   */
  def mandatoryService =
    if (_current.isDefined) _current.get
    else throw new IllegalStateException("co current CamelService")

  /**
   * Returns <code>Some(CamelService)</code> (containing the current CamelService)
   * if this <code>CamelService</code>has been started, <code>None</code> otherwise.
   * <p>
   * Java API
   */
  def getMandatoryService = mandatoryService

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
   * Creates a new CamelService instance.
   */
  def createCamelService: CamelService = new CamelService {}

  /**
   * Creates a new CamelService instance and initializes it with the given CamelContext.
   */
  def createCamelService(camelContext: CamelContext): CamelService = {
    CamelContextManager.init(camelContext)
    createCamelService
  }
}
