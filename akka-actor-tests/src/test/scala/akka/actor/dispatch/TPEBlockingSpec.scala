/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.dispatch

import akka.dispatch.ThreadPoolConfig
import akka.testkit.{ DefaultTimeout, AkkaSpec }
import java.util.concurrent.{ Executors, RejectedExecutionException }
import scala.concurrent.{ ExecutionContext, Promise, Await, Future }

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class TPEBlockingSpec extends AkkaSpec() with DefaultTimeout {

  "A ThreadPoolExecutor" must {
    "support BlockContext" in {
      val result = Promise[Unit]()
      implicit val ec = ExecutionContext.fromExecutorService(ThreadPoolConfig(corePoolSize = 3,
        maxPoolSize = 3,
        minUnblockedThreads = 1).createExecutorServiceFactory("TPEBlockingSpec", Executors.defaultThreadFactory).
        createExecutorService())

      try {
        val f1 = Future { Await.result(result.future, timeout.duration) }
        val f2 = Future { Await.result(result.future, timeout.duration) }
        val f3 = Future { Await.result(result.future, timeout.duration) }
        Future { result.success(()) }
        Await.result(f1, timeout.duration) should be(())
        Await.result(f2, timeout.duration) should be(())
        intercept[RejectedExecutionException] {
          Await.result(f3, timeout.duration)
        }.getMessage should be("Blocking rejected due to insufficient unblocked threads in pool. Needs at least 1")
      } finally {
        ec.shutdownNow()
      }
    }
  }

}
