package akka.dispatch

import java.util.concurrent.{ ExecutorService, Executor, Executors }
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent._
import akka.testkit.{ TestLatch, AkkaSpec, DefaultTimeout }

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ExecutionContextSpec extends AkkaSpec with DefaultTimeout {

  "An ExecutionContext" must {

    "be instantiable" in {
      val es = Executors.newCachedThreadPool()
      try {
        val executor: Executor with ExecutionContext = ExecutionContext.fromExecutor(es)
        executor must not be (null)

        val executorService: ExecutorService with ExecutionContext = ExecutionContext.fromExecutorService(es)
        executorService must not be (null)

        val jExecutor: ExecutionContextExecutor = ExecutionContext.fromExecutor(es)
        jExecutor must not be (null)

        val jExecutorService: ExecutionContextExecutorService = ExecutionContexts.fromExecutorService(es)
        jExecutorService must not be (null)
      } finally {
        es.shutdown
      }
    }

    "be able to use Batching" in {
      system.dispatcher.isInstanceOf[BatchingExecutor] must be(true)

      import system.dispatcher

      def batchable[T](f: ⇒ T)(implicit ec: ExecutionContext): Unit = ec.execute(new Batchable {
        override def isBatchable = true
        override def run: Unit = f
      })

      val p = Promise[Unit]()
      batchable {
        val lock, callingThreadLock, count = new AtomicInteger(0)
        callingThreadLock.compareAndSet(0, 1) // Enable the lock
        (1 to 100) foreach { i ⇒
          batchable {
            if (callingThreadLock.get != 0) p.tryFailure(new IllegalStateException("Batch was executed inline!"))
            else if (count.incrementAndGet == 100) p.trySuccess(()) //Done
            else if (lock.compareAndSet(0, 1)) {
              try Thread.sleep(10) finally lock.compareAndSet(1, 0)
            } else p.tryFailure(new IllegalStateException("Executed batch in parallel!"))
          }
        }
        callingThreadLock.compareAndSet(1, 0) // Disable the lock
      }
      Await.result(p.future, timeout.duration) must be === ()
    }

    "be able to avoid starvation when Batching is used and Await/blocking is called" in {
      system.dispatcher.isInstanceOf[BatchingExecutor] must be(true)
      import system.dispatcher

      def batchable[T](f: ⇒ T)(implicit ec: ExecutionContext): Unit = ec.execute(new Batchable {
        override def isBatchable = true
        override def run: Unit = f
      })

      val latch = TestLatch(101)
      batchable {
        (1 to 100) foreach { i ⇒
          batchable {
            val deadlock = TestLatch(1)
            batchable { deadlock.open() }
            Await.ready(deadlock, timeout.duration)
            latch.countDown()
          }
        }
        latch.countDown()
      }
      Await.ready(latch, timeout.duration)
    }
  }
}
