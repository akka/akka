package akka.dispatch

import akka.testkit.AkkaSpec
import akka.testkit.DefaultTimeout
import java.util.concurrent.{ ExecutorService, Executor, Executors }

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

        val jExecutor: ExecutionContextExecutor = ExecutionContexts.fromExecutor(es)
        jExecutor must not be (null)

        val jExecutorService: ExecutionContextExecutorService = ExecutionContexts.fromExecutorService(es)
        jExecutorService must not be (null)

      } finally {
        es.shutdown
      }
    }
  }
}
