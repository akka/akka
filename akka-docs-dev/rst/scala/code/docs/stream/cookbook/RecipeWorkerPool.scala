package docs.stream.cookbook

import akka.stream.scaladsl._

import scala.concurrent.Await
import scala.concurrent.duration._

class RecipeWorkerPool extends RecipeSpec {

  "Recipe for a pool of workers" must {

    "work" in {
      val data = Source(List("1", "2", "3", "4", "5"))
      type Result = String

      val worker = Flow[String].map(_ + " done")

      //#worker-pool
      def balancer[In, Out](worker: Flow[In, Out], workerCount: Int): Flow[In, Out] = {
        import akka.stream.scaladsl.FlowGraphImplicits._

        Flow[In, Out]() { implicit graphBuilder =>
          val jobsIn = UndefinedSource[In]
          val resultsOut = UndefinedSink[Out]

          val balancer = Balance[In](waitForAllDownstreams = true)
          val merge = Merge[Out]

          jobsIn ~> balancer // Jobs are fed into the balancer
          merge ~> resultsOut // the merged results are sent out

          for (_ <- 1 to workerCount) {
            // for each worker, add an edge from the balancer to the worker, then wire
            // it to the merge element
            balancer ~> worker ~> merge
          }

          (jobsIn, resultsOut)
        }
      }

      val processedJobs: Source[Result] = data.via(balancer(worker, 3))
      //#worker-pool

      Await.result(processedJobs.grouped(10).runWith(Sink.head), 3.seconds).toSet should be(Set(
        "1 done", "2 done", "3 done", "4 done", "5 done"))

    }

  }

}
