/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.testkit

import akka.util.Duration
import java.util.concurrent.{ CountDownLatch, TimeUnit }

class TestLatchTimeoutException(message: String) extends RuntimeException(message)
class TestLatchNoTimeoutException(message: String) extends RuntimeException(message)

/**
 * A count down latch wrapper for use in testing.
 * It always uses a timeout when waiting and timeouts are specified as durations.
 * There's a default timeout of 5 seconds and the default count is 1.
 * Timeouts will always throw an exception (no need to wrap in assert in tests).
 * Timeouts are multiplied by the testing time factor for Jenkins builds.
 */
object TestLatch {
  val DefaultTimeout = Duration(5, TimeUnit.SECONDS)

  def apply(count: Int = 1) = new TestLatch(count)
}

class TestLatch(count: Int = 1) {
  private var latch = new CountDownLatch(count)

  def countDown() = latch.countDown()

  def open() = countDown()

  def await(): Boolean = await(TestLatch.DefaultTimeout)

  def await(timeout: Duration): Boolean = {
    val opened = latch.await(Testing.testTime(timeout.toNanos), TimeUnit.NANOSECONDS)
    if (!opened) throw new TestLatchTimeoutException(
      "Timeout of %s with time factor of %s" format (timeout.toString, Duration.timeFactor))
    opened
  }

  /**
   * Timeout is expected. Throws exception if latch is opened before timeout.
   */
  def awaitTimeout(timeout: Duration = TestLatch.DefaultTimeout) = {
    val opened = latch.await(Testing.testTime(timeout.toNanos), TimeUnit.NANOSECONDS)
    if (opened) throw new TestLatchNoTimeoutException(
      "Latch opened before timeout of %s with time factor of %s" format (timeout.toString, Duration.timeFactor))
    opened
  }

  def reset() = latch = new CountDownLatch(count)
}

