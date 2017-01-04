/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.stream.{ FlowShape, ActorMaterializer, ActorMaterializerSettings, OverflowStrategy }
import akka.stream.testkit._
import akka.stream.testkit.Utils._
import akka.stream.testkit.scaladsl._
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time._

import scala.collection.immutable

class FlowJoinSpec extends StreamSpec(ConfigFactory.parseString("akka.loglevel=INFO")) {

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val materializer = ActorMaterializer(settings)

  implicit val defaultPatience =
    PatienceConfig(timeout = Span(2, Seconds), interval = Span(200, Millis))

  "A Flow using join" must {
    "allow for cycles" in assertAllStagesStopped {
      val end = 47
      val (even, odd) = (0 to end).partition(_ % 2 == 0)
      val result = Set() ++ even ++ odd ++ odd.map(_ * 10)
      val source = Source(0 to end)
      val probe = TestSubscriber.manualProbe[Seq[Int]]()

      val flow1 = Flow.fromGraph(GraphDSL.create() { implicit b ⇒
        import GraphDSL.Implicits._
        val merge = b.add(Merge[Int](2))
        val broadcast = b.add(Broadcast[Int](2))
        source ~> merge.in(0)
        merge.out ~> broadcast.in
        broadcast.out(0).grouped(1000) ~> Sink.fromSubscriber(probe)

        FlowShape(merge.in(1), broadcast.out(1))
      })

      val flow2 = Flow[Int]
        .filter(_ % 2 == 1)
        .map(_ * 10)
        .buffer((end + 1) / 2, OverflowStrategy.backpressure)
        .take((end + 1) / 2)

      val mm = flow1.join(flow2).run()

      val sub = probe.expectSubscription()
      sub.request(1)
      probe.expectNext().toSet should be(result)
      sub.cancel()
    }

    "allow for merge cycle" in assertAllStagesStopped {
      val source = Source.single("lonely traveler")

      val flow1 = Flow.fromGraph(GraphDSL.create(Sink.head[String]) { implicit b ⇒ sink ⇒
        import GraphDSL.Implicits._
        val merge = b.add(Merge[String](2))
        val broadcast = b.add(Broadcast[String](2, eagerCancel = true))
        source ~> merge.in(0)
        merge.out ~> broadcast.in
        broadcast.out(0) ~> sink

        FlowShape(merge.in(1), broadcast.out(1))
      })

      whenReady(flow1.join(Flow[String]).run())(_ shouldBe "lonely traveler")
    }

    "allow for merge preferred cycle" in assertAllStagesStopped {
      val source = Source.single("lonely traveler")

      val flow1 = Flow.fromGraph(GraphDSL.create(Sink.head[String]) { implicit b ⇒ sink ⇒
        import GraphDSL.Implicits._
        val merge = b.add(MergePreferred[String](1))
        val broadcast = b.add(Broadcast[String](2, eagerCancel = true))
        source ~> merge.preferred
        merge.out ~> broadcast.in
        broadcast.out(0) ~> sink

        FlowShape(merge.in(0), broadcast.out(1))
      })

      whenReady(flow1.join(Flow[String]).run())(_ shouldBe "lonely traveler")
    }

    "allow for zip cycle" in assertAllStagesStopped {
      val source = Source(immutable.Seq("traveler1", "traveler2"))

      val flow = Flow.fromGraph(GraphDSL.create(TestSink.probe[(String, String)]) { implicit b ⇒ sink ⇒
        import GraphDSL.Implicits._
        val zip = b.add(Zip[String, String])
        val broadcast = b.add(Broadcast[(String, String)](2))
        source ~> zip.in0
        zip.out ~> broadcast.in
        broadcast.out(0) ~> sink

        FlowShape(zip.in1, broadcast.out(1))
      })

      val feedback = Flow.fromGraph(GraphDSL.create(Source.single("ignition")) { implicit b ⇒ ignition ⇒
        import GraphDSL.Implicits._
        val flow = b.add(Flow[(String, String)].map(_._1))
        val merge = b.add(Merge[String](2))

        ignition ~> merge.in(0)
        flow ~> merge.in(1)

        FlowShape(flow.in, merge.out)
      })

      val probe = flow.join(feedback).run()
      probe.requestNext(("traveler1", "ignition"))
      probe.requestNext(("traveler2", "traveler1"))
    }

    "allow for concat cycle" in assertAllStagesStopped {
      val flow = Flow.fromGraph(GraphDSL.create(TestSource.probe[String](system), Sink.head[String])(Keep.both) { implicit b ⇒ (source, sink) ⇒
        import GraphDSL.Implicits._
        val concat = b.add(Concat[String](2))
        val broadcast = b.add(Broadcast[String](2, eagerCancel = true))
        source ~> concat.in(0)
        concat.out ~> broadcast.in
        broadcast.out(0) ~> sink

        FlowShape(concat.in(1), broadcast.out(1))
      })

      val (probe, result) = flow.join(Flow[String]).run()
      probe.sendNext("lonely traveler")
      whenReady(result) { r ⇒
        r shouldBe "lonely traveler"
        probe.sendComplete()
      }
    }

    "allow for interleave cycle" in assertAllStagesStopped {
      val source = Source.single("lonely traveler")

      val flow1 = Flow.fromGraph(GraphDSL.create(Sink.head[String]) { implicit b ⇒ sink ⇒
        import GraphDSL.Implicits._
        val merge = b.add(Interleave[String](2, 1))
        val broadcast = b.add(Broadcast[String](2, eagerCancel = true))
        source ~> merge.in(0)
        merge.out ~> broadcast.in
        broadcast.out(0) ~> sink

        FlowShape(merge.in(1), broadcast.out(1))
      })

      whenReady(flow1.join(Flow[String]).run())(_ shouldBe "lonely traveler")
    }
  }
}
