/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.testkit

import akka.util.Duration
import java.util.concurrent.{ CyclicBarrier, TimeUnit, TimeoutException }

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

  def await(): Unit = await(TestBarrier.DefaultTimeout)

  def await(timeout: Duration): Unit = {
    try {
      barrier.await(Testing.testTime(timeout.toNanos), TimeUnit.NANOSECONDS)
    } catch {
      case e: TimeoutException ⇒
        throw new TestBarrierTimeoutException("Timeout of %s and time factor of %s" format (timeout.toString, Duration.timeFactor))
    }
  }

  def reset = barrier.reset
}
