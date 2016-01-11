package docs.stream.cookbook

import akka.stream.{ ActorMaterializerSettings, ActorMaterializer }
import akka.stream.scaladsl._
import akka.stream.testkit._

import scala.collection.immutable
import scala.concurrent.Await
import scala.concurrent.duration._

class RecipeCollectingMetrics extends RecipeSpec {
  import HoldOps._
  implicit val m2 = ActorMaterializer(ActorMaterializerSettings(system).withInputBuffer(1, 1))

  "Recipe for periodically collecting metrics" must {

    "work" in {
      //      type Tick = Unit
      //
      //      val loadPub = TestPublisher.manualProbe[Int]()
      //      val tickPub = TestPublisher.manualProbe[Tick]()
      //      val reportTicks = Source.fromPublisher(tickPub)
      //      val loadUpdates = Source.fromPublisher(loadPub)
      //      val futureSink = Sink.head[immutable.Seq[String]]
      //      val sink = Flow[String].grouped(10).to(futureSink)
      //
      //      //#periodic-metrics-collection
      //      val currentLoad = loadUpdates.transform(() => new HoldWithWait)
      //
      //      val graph = GraphDSL { implicit builder =>
      //        import FlowGraphImplicits._
      //        val collector = ZipWith[Int, Tick, String](
      //          (load: Int, tick: Tick) => s"current load is $load")
      //
      //        currentLoad ~> collector.left
      //        reportTicks ~> collector.right
      //
      //        collector.out ~> sink
      //      }
      //      //#periodic-metrics-collection
      //
      //      val reports = graph.run().get(futureSink)
      //      val manualLoad = new StreamTestKit.AutoPublisher(loadPub)
      //      val manualTick = new StreamTestKit.AutoPublisher(tickPub)
      //
      //      // Prefetch elimination
      //      manualTick.sendNext(())
      //
      //      manualLoad.sendNext(53)
      //      manualLoad.sendNext(61)
      //      manualTick.sendNext(())
      //
      //      manualLoad.sendNext(44)
      //      manualLoad.sendNext(54)
      //      manualLoad.sendNext(78)
      //      Thread.sleep(500)
      //
      //      manualTick.sendNext(())
      //
      //      manualTick.sendComplete()
      //
      //      Await.result(reports, 3.seconds) should be(List("current load is 53", "current load is 61", "current load is 78"))

      //      Periodically collect values of metrics expressed as stream of updates
      //        ---------------------------------------------------------------------
      //
      //      **Situation:** Given performance counters expressed as a stream of updates we want to gather a periodic report of these.
      //        We do not want to backpressure the counter updates but always take the last value instead. Whenever we don't have a new counter
      //      value we want to repeat the last value.
      //
      //      This recipe uses the :class:`HoldWithWait` recipe introduced previously. We use this element to gather updates from
      //        the counter stream and store the final value, and also repeat this final value if no update is received between
      //      metrics collection rounds.
      //
      //        To finish the recipe, we simply use :class:`ZipWith` to trigger reading the latest value from the ``currentLoad``
      //        stream whenever a new ``Tick`` arrives on the stream of ticks, ``reportTicks``.
      //
      //        .. includecode:: code/docs/stream/cookbook/RecipeCollectingMetrics.scala#periodic-metrics-collection
      //
      //        .. warning::
      //        In order for this recipe to work the buffer size for the :class:`ZipWith` must be set to 1. The reason for this is
      //        explained in the "Buffering" section of the documentation.

      // FIXME: This recipe does only work with buffer size of 0, which is only available if graph fusing is implemented
      pending
    }

  }

}
