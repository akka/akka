/*
 * Copyright (C) 2025 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.dispatch

import java.util.concurrent.{ CountDownLatch, ForkJoinPool, ForkJoinTask, TimeUnit }

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import akka.dispatch.ForkJoinExecutorConfigurator.AkkaForkJoinPool

object AkkaForkJoinPoolSpec {
  final class RunnableTask(latch: CountDownLatch) extends ForkJoinTask[Unit] with Runnable {
    override def getRawResult(): Unit = ()
    override def setRawResult(value: Unit): Unit = ()
    override def exec(): Boolean = { run(); true }
    override def run(): Unit = latch.countDown()
  }
}

// re-submitting the same ForkJoinTask instance can corrupt the FJP work queue, issue #33019
class AkkaForkJoinPoolSpec extends AnyWordSpec with Matchers {
  import AkkaForkJoinPoolSpec._

  "Mailbox" must {
    "not be a ForkJoinTask" in {
      classOf[ForkJoinTask[_]].isAssignableFrom(classOf[Mailbox]) shouldBe false
    }
  }

  "AkkaForkJoinPool" must {
    "wrap submitted ForkJoinTasks in a fresh task" in {
      val pool =
        new AkkaForkJoinPool(1, ForkJoinPool.defaultForkJoinWorkerThreadFactory, MonitorableThreadFactory.doNothing)
      try {
        val latch = new CountDownLatch(1)
        val task = new RunnableTask(latch)
        pool.execute(task: Runnable)
        latch.await(3, TimeUnit.SECONDS) shouldBe true
        // only run through a wrapper, the submitted task itself was never executed by the pool
        task.isDone shouldBe false
      } finally pool.shutdownNow()
    }
  }
}
