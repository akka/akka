package akka.camel

/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */


import _root_.._
import _root_.java.util.concurrent.CountDownLatch
import _root_.java.util.concurrent.TimeUnit


import _root_.akka.japi.{SideEffect, Option => JOption}
import _root_.akka.actor.Actor


/**
 * Optional functionality
 */
trait AwaitActivation{
  self: CamelService =>
  /**
   * Waits for an expected number (<code>count</code>) of consumer actor endpoints to be activated
   * during execution of <code>f</code>. The wait-timeout is by default 10 seconds. Other timeout
   * values can be set via the <code>timeout</code> and <code>timeUnit</code> parameters.
   */
  def awaitEndpointActivation(count: Int, timeout: Long = 10, timeUnit: TimeUnit = TimeUnit.SECONDS)(f: => Unit): Boolean = {
    val activation = expectEndpointActivationCount(count)
    f;  activation.await(timeout, timeUnit)
  }

  /**
   * Waits for an expected number (<code>count</code>) of consumer actor endpoints to be de-activated
   * during execution of <code>f</code>. The wait-timeout is by default 10 seconds. Other timeout
   * values can be set via the <code>timeout</code> and <code>timeUnit</code>
   * parameters.
   */
  def awaitEndpointDeactivation(count: Int, timeout: Long = 10, timeUnit: TimeUnit = TimeUnit.SECONDS)(f: => Unit): Boolean = {
    val activation = expectEndpointDeactivationCount(count)
    f;  activation.await(timeout, timeUnit)
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
  private def expectEndpointActivationCount(count: Int): CountDownLatch = unsupported
//    (activationTracker !! SetExpectedActivationCount(count)).as[CountDownLatch].get

  /**
   * Sets an expectation on the number of upcoming endpoint de-activations and returns
   * a CountDownLatch that can be used to wait for the de-activations to occur. Endpoint
   * de-activations that occurred in the past are not considered.
   */
  private def expectEndpointDeactivationCount(count: Int): CountDownLatch = unsupported
//    (activationTracker !! SetExpectedDeactivationCount(count)).as[CountDownLatch].get

}

/**
 * Tracks <code>EndpointActivated</code> and <code>EndpointDectivated</code> events. Used to wait for a
 * certain number of endpoints activations and de-activations to occur.
 *
 * @see SetExpectedActivationCount
 * @see SetExpectedDeactivationCount
 *
 * @author Martin Krasser
 */
private[camel] class ActivationTracker extends Actor {
  private var activationLatch = new CountDownLatch(0)
  private var deactivationLatch = new CountDownLatch(0)

  def receive = {
    case SetExpectedActivationCount(num) => {
      activationLatch = new CountDownLatch(num)
      self.reply(activationLatch)
    }
    case SetExpectedDeactivationCount(num) => {
      deactivationLatch = new CountDownLatch(num)
      self.reply(deactivationLatch)
    }
    case EndpointActivated =>   activationLatch.countDown
    case EndpointDeactivated => deactivationLatch.countDown
  }
}

/**
 * Command message that sets the number of expected endpoint activations on <code>ActivationTracker</code>.
 */
private[camel] case class SetExpectedActivationCount(num: Int)

/**
 * Command message that sets the number of expected endpoint de-activations on <code>ActivationTracker</code>.
 */
private[camel] case class SetExpectedDeactivationCount(num: Int)

/**
 * Event message indicating that a single endpoint has been activated.
 */
private[camel] case class EndpointActivated()

/**
 * Event message indicating that a single endpoint has been de-activated.
 */
private[camel] case class EndpointDeactivated()

