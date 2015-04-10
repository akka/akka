package docs.stream.cookbook

import akka.stream.scaladsl._
import akka.testkit.TestProbe

import scala.concurrent.Await
import scala.concurrent.duration._

class RecipeWorkerPool extends RecipeSpec {

  "Recipe for a pool of workers" must {

    "work" in {
      val myJobs = Source(List("1", "2", "3", "4", "5"))
      type Result = String

      val worker = Flow[String].map(_ + " done")

      //#worker-pool
      def balancer[In, Out](worker: Flow[In, Out, Any], workerCount: Int): Flow[In, Out, Unit] = {
        import FlowGraph.Implicits._

        Flow() { implicit b =>
          val balancer = b.add(Balance[In](workerCount, waitForAllDownstreams = true))
          val merge = b.add(Merge[Out](workerCount))

          for (_ <- 1 to workerCount) {
            // for each worker, add an edge from the balancer to the worker, then wire
            // it to the merge element
            balancer ~> worker ~> merge
          }

          (balancer.in, merge.out)
        }
      }

      val processedJobs: Source[Result, Unit] = myJobs.via(balancer(worker, 3))
      //#worker-pool

      Await.result(processedJobs.grouped(10).runWith(Sink.head), 3.seconds).toSet should be(Set(
        "1 done", "2 done", "3 done", "4 done", "5 done"))

    }

  }

}
