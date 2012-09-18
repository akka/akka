/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.testkit

import scala.concurrent.util.Duration
import java.util.concurrent.{ CyclicBarrier, TimeUnit, TimeoutException }
import akka.actor.ActorSystem
import scala.concurrent.util.FiniteDuration

class TestBarrierTimeoutException(message: String) extends RuntimeException(message)

/**
 * A cyclic barrier wrapper for use in testing.
 * It always uses a timeout when waiting and timeouts are specified as durations.
 * Timeouts will always throw an exception. The default timeout is 5 seconds.
 * Timeouts are multiplied by the testing time factor for Jenkins builds.
 */
object TestBarrier {
  val DefaultTimeout = Duration(5, TimeUnit.SECONDS)

  def apply(count: Int) = new TestBarrier(count)
}

class TestBarrier(count: Int) {
  private val barrier = new CyclicBarrier(count)

  def await()(implicit system: ActorSystem): Unit = await(TestBarrier.DefaultTimeout)

  def await(timeout: FiniteDuration)(implicit system: ActorSystem) {
    try {
      barrier.await(timeout.dilated.toNanos, TimeUnit.NANOSECONDS)
    } catch {
      case e: TimeoutException ⇒
        throw new TestBarrierTimeoutException("Timeout of %s and time factor of %s"
          format (timeout.toString, TestKitExtension(system).TestTimeFactor))
    }
  }

  def reset = barrier.reset
}
