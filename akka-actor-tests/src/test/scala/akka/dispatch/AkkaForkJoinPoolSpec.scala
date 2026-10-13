/*
 * Copyright (C) 2025 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.dispatch

import java.util.concurrent.{ CountDownLatch, ForkJoinPool, ForkJoinTask, TimeUnit }
import java.util.concurrent.atomic.AtomicInteger

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import akka.dispatch.ForkJoinExecutorConfigurator.AkkaForkJoinPool

object AkkaForkJoinPoolSpec {

  /**
   * Models the Mailbox: a ForkJoinTask that never completes and is re-submitted on every scheduling.
   */
  final class ReusableTask(pool: ForkJoinPool, totalRuns: Int, resubmit: Boolean)
      extends ForkJoinTask[Unit]
      with Runnable {
    val runs = new AtomicInteger
    val execs = new AtomicInteger
    val done = new CountDownLatch(totalRuns)

    override def getRawResult(): Unit = ()
    override def setRawResult(unit: Unit): Unit = ()
    override def exec(): Boolean = {
      execs.incrementAndGet()
      run()
      false
    }

    override def run(): Unit = {
      val n = runs.incrementAndGet()
      done.countDown()
      if (resubmit && n < totalRuns)
        pool.execute(this: Runnable)
    }
  }
}

class AkkaForkJoinPoolSpec extends AnyWordSpec with Matchers {
  import AkkaForkJoinPoolSpec._

  private def withPool(f: AkkaForkJoinPool => Unit): Unit = {
    val pool =
      new AkkaForkJoinPool(4, ForkJoinPool.defaultForkJoinWorkerThreadFactory, MonitorableThreadFactory.doNothing)
    try f(pool)
    finally {
      pool.shutdownNow()
      pool.awaitTermination(10, TimeUnit.SECONDS)
    }
  }

  "AkkaForkJoinPool" must {

    "submit a fresh task for each external submission of the same ForkJoinTask instance" in withPool { pool =>
      val totalRuns = 1000
      val task = new ReusableTask(pool, totalRuns, resubmit = false)
      (1 to totalRuns).foreach { _ =>
        val before = task.runs.get
        pool.execute(task: Runnable)
        while (task.runs.get == before) Thread.onSpinWait()
      }
      task.done.await(10, TimeUnit.SECONDS) should ===(true)
      task.runs.get should ===(totalRuns)
      // exec of the submitted instance itself must never be called by the pool, see issue #33019
      task.execs.get should ===(0)
    }

    "submit a fresh task when the same ForkJoinTask instance is re-submitted from a worker thread" in withPool { pool =>
      val totalRuns = 10000
      val task = new ReusableTask(pool, totalRuns, resubmit = true)
      pool.execute(task: Runnable)
      task.done.await(10, TimeUnit.SECONDS) should ===(true)
      task.runs.get should ===(totalRuns)
      task.execs.get should ===(0)
    }

    "reject null runnables" in withPool { pool =>
      intercept[NullPointerException] {
        pool.execute(null: Runnable)
      }
    }
  }
}
