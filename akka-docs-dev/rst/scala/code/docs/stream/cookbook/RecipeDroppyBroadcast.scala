package docs.stream.cookbook

import akka.stream.OverflowStrategy
import akka.stream.scaladsl._
import akka.stream.testkit.StreamTestKit.SubscriberProbe

import scala.collection.immutable
import scala.concurrent.Await
import scala.concurrent.duration._

class RecipeDroppyBroadcast extends RecipeSpec {

  "Recipe for a droppy broadcast" must {
    "work" in {
      val myElements = Source(immutable.Iterable.tabulate(100)(_ + 1))

      val sub1 = SubscriberProbe[Int]()
      val sub2 = SubscriberProbe[Int]()
      val mySink1 = Sink(sub1)
      val mySink2 = Sink(sub2)
      val futureSink = Sink.head[Seq[Int]]
      val mySink3 = Flow[Int].grouped(200).to(futureSink)

      //#droppy-bcast
      // Makes a sink drop elements if too slow
      def droppySink[T](sink: Sink[T], bufferSize: Int): Sink[T] = {
        Flow[T].buffer(bufferSize, OverflowStrategy.dropHead).to(sink)
      }

      import FlowGraphImplicits._
      val graph = FlowGraph { implicit builder =>
        val bcast = Broadcast[Int]

        myElements ~> bcast

        bcast ~> droppySink(mySink1, 10)
        bcast ~> droppySink(mySink2, 10)
        bcast ~> droppySink(mySink3, 10)
      }
      //#droppy-bcast

      Await.result(graph.run().get(futureSink), 3.seconds).sum should be(5050)

      sub1.expectSubscription().request(10)
      sub2.expectSubscription().request(10)

      for (i <- 91 to 100) {
        sub1.expectNext(i)
        sub2.expectNext(i)
      }

      sub1.expectComplete()
      sub2.expectComplete()

    }
  }

}
