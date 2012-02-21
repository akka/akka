/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.testkit

import akka.util.Duration
import akka.actor.ActorSystem
import akka.dispatch.Await.{ CanAwait, Awaitable }
import java.util.concurrent.{ TimeoutException, CountDownLatch, TimeUnit }

/**
 * A count down latch wrapper for use in testing.
 * It always uses a timeout when waiting and timeouts are specified as durations.
 * There's a default timeout of 5 seconds and the default count is 1.
 * Timeouts will always throw an exception (no need to wrap in assert in tests).
 * Timeouts are multiplied by the testing time factor for Jenkins builds.
 */
object TestLatch {
  val DefaultTimeout = Duration(5, TimeUnit.SECONDS)

  def apply(count: Int = 1)(implicit system: ActorSystem) = new TestLatch(count)
}

class TestLatch(count: Int = 1)(implicit system: ActorSystem) extends Awaitable[Unit] {
  private var latch = new CountDownLatch(count)

  def countDown() = latch.countDown()
  def isOpen: Boolean = latch.getCount == 0
  def open() = while (!isOpen) countDown()
  def reset() = latch = new CountDownLatch(count)

  @throws(classOf[TimeoutException])
  def ready(atMost: Duration)(implicit permit: CanAwait) = {
    val opened = latch.await(atMost.dilated.toNanos, TimeUnit.NANOSECONDS)
    if (!opened) throw new TimeoutException(
      "Timeout of %s with time factor of %s" format (atMost.toString, TestKitExtension(system).TestTimeFactor))
    this
  }
  @throws(classOf[Exception])
  def result(atMost: Duration)(implicit permit: CanAwait): Unit = {
    ready(atMost)
  }
}

