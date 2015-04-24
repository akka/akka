/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.ActorFlowMaterializer
import akka.stream.ActorFlowMaterializerSettings
import akka.stream.testkit._
import akka.stream._
import org.reactivestreams.Subscriber

object GraphFlowSpec {
  val source1 = Source(0 to 3)

  val partialGraph = FlowGraph.partial() { implicit b ⇒
    import FlowGraph.Implicits._
    val source2 = Source(4 to 9)
    val source3 = Source.empty[Int]
    val source4 = Source.empty[String]

    val inMerge = b.add(Merge[Int](2))
    val outMerge = b.add(Merge[String](2))
    val m2 = b.add(Merge[Int](2))

    inMerge.out.map(_ * 2) ~> m2.in(0)
    m2.out.map(_ / 2).map(i ⇒ (i + 1).toString) ~> outMerge.in(0)

    source2 ~> inMerge.in(0)
    source3 ~> m2.in(1)
    source4 ~> outMerge.in(1)
    FlowShape(inMerge.in(1), outMerge.out)
  }

  val stdRequests = 10
  val stdResult = Set(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
}

class GraphFlowSpec extends AkkaSpec {

  import GraphFlowSpec._

  val settings = ActorFlowMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val materializer = ActorFlowMaterializer(settings)

  def validateProbe(probe: TestSubscriber.ManualProbe[Int], requests: Int, result: Set[Int]): Unit = {
    val subscription = probe.expectSubscription()

    val collected = (1 to requests).map { _ ⇒
      subscription.request(1)
      probe.expectNext()
    }.toSet

    collected should be(result)
    probe.expectComplete()

  }

  "FlowGraphs" when {
    "turned into flows" should {
      "work with a Source and Sink" in {
        val probe = TestSubscriber.manualProbe[Int]()

        val flow = Flow(partialGraph) { implicit b ⇒
          partial ⇒
            import FlowGraph.Implicits._

            (partial.inlet, partial.outlet.map(_.toInt).outlet)
        }

        source1.via(flow).to(Sink(probe)).run()

        validateProbe(probe, stdRequests, stdResult)
      }

      "be transformable with a Pipe" in {
        val probe = TestSubscriber.manualProbe[Int]()

        val flow = Flow(partialGraph) { implicit b ⇒
          partial ⇒
            (partial.inlet, partial.outlet)
        }

        source1.via(flow).map(_.toInt).to(Sink(probe)).run()

        validateProbe(probe, stdRequests, stdResult)
      }

      "work with another GraphFlow" in {
        val probe = TestSubscriber.manualProbe[Int]()

        val flow1 = Flow(partialGraph) { implicit b ⇒
          partial ⇒
            (partial.inlet, partial.outlet)
        }

        val flow2 = Flow(Flow[String].map(_.toInt)) { implicit b ⇒
          importFlow ⇒
            (importFlow.inlet, importFlow.outlet)
        }

        source1.via(flow1).via(flow2).to(Sink(probe)).run()

        validateProbe(probe, stdRequests, stdResult)
      }

      "be reusable multiple times" in {
        val probe = TestSubscriber.manualProbe[Int]()

        val flow = Flow(Flow[Int].map(_ * 2)) { implicit b ⇒
          importFlow ⇒
            (importFlow.inlet, importFlow.outlet)
        }

        FlowGraph.closed() { implicit b ⇒
          import FlowGraph.Implicits._
          Source(1 to 5) ~> flow ~> flow ~> Sink(probe)
        }.run()

        validateProbe(probe, 5, Set(4, 8, 12, 16, 20))
      }
    }

    "turned into sources" should {
      "work with a Sink" in {
        val probe = TestSubscriber.manualProbe[Int]()

        val source = Source(partialGraph) { implicit b ⇒
          partial ⇒
            import FlowGraph.Implicits._
            source1 ~> partial.inlet
            partial.outlet.map(_.toInt).outlet
        }

        source.to(Sink(probe)).run()

        validateProbe(probe, stdRequests, stdResult)
      }

      "work with a Sink when having KeyedSource inside" in {
        val probe = TestSubscriber.manualProbe[Int]()

        val source = Source.apply(Source.subscriber[Int]) { implicit b ⇒
          subSource ⇒
            subSource.outlet
        }

        val mm: Subscriber[Int] = source.to(Sink(probe)).run()
        source1.to(Sink(mm)).run()

        validateProbe(probe, 4, (0 to 3).toSet)
      }

      "be transformable with a Pipe" in {

        val probe = TestSubscriber.manualProbe[Int]()

        val source = Source(partialGraph) { implicit b ⇒
          partial ⇒
            import FlowGraph.Implicits._
            source1 ~> partial.inlet
            partial.outlet
        }

        source.map(_.toInt).to(Sink(probe)).run()

        validateProbe(probe, stdRequests, stdResult)
      }

      "work with an GraphFlow" in {
        val probe = TestSubscriber.manualProbe[Int]()

        val source = Source(partialGraph) { implicit b ⇒
          partial ⇒
            import FlowGraph.Implicits._
            source1 ~> partial.inlet
            partial.outlet
        }

        val flow = Flow(Flow[String].map(_.toInt)) { implicit b ⇒
          importFlow ⇒
            (importFlow.inlet, importFlow.outlet)
        }

        source.via(flow).to(Sink(probe)).run()

        validateProbe(probe, stdRequests, stdResult)
      }

      "be reusable multiple times" in {
        val probe = TestSubscriber.manualProbe[Int]()

        val source = Source(Source(1 to 5)) { implicit b ⇒
          s ⇒
            import FlowGraph.Implicits._
            s.outlet.map(_ * 2).outlet
        }

        FlowGraph.closed(source, source)(Keep.both) { implicit b ⇒
          (s1, s2) ⇒
            import FlowGraph.Implicits._
            val merge = b.add(Merge[Int](2))
            s1.outlet ~> merge.in(0)
            merge.out ~> Sink(probe)
            s2.outlet.map(_ * 10) ~> merge.in(1)
        }.run()

        validateProbe(probe, 10, Set(2, 4, 6, 8, 10, 20, 40, 60, 80, 100))
      }
    }

    "turned into sinks" should {
      "work with a Source" in {
        val probe = TestSubscriber.manualProbe[Int]()

        val sink = Sink(partialGraph) { implicit b ⇒
          partial ⇒
            import FlowGraph.Implicits._
            partial.outlet.map(_.toInt) ~> Sink(probe)
            partial.inlet
        }

        source1.to(sink).run()

        validateProbe(probe, stdRequests, stdResult)
      }

      "work with a Source when having KeyedSink inside" in {
        val probe = TestSubscriber.manualProbe[Int]()
        val pubSink = Sink.publisher[Int]

        val sink = Sink(pubSink) { implicit b ⇒
          p ⇒
            p.inlet
        }

        val mm = source1.runWith(sink)
        Source(mm).to(Sink(probe)).run()

        validateProbe(probe, 4, (0 to 3).toSet)
      }

      "be transformable with a Pipe" in {
        val probe = TestSubscriber.manualProbe[Int]()

        val sink = Sink(partialGraph, Flow[String].map(_.toInt))(Keep.both) { implicit b ⇒
          (partial, flow) ⇒
            import FlowGraph.Implicits._
            flow.outlet ~> partial.inlet
            partial.outlet.map(_.toInt) ~> Sink(probe)
            flow.inlet
        }

        val iSink = Flow[Int].map(_.toString).to(sink)
        source1.to(iSink).run()

        validateProbe(probe, stdRequests, stdResult)
      }

      "work with a GraphFlow" in {

        val probe = TestSubscriber.manualProbe[Int]()

        val flow = Flow(partialGraph) { implicit b ⇒
          partial ⇒
            (partial.inlet, partial.outlet)
        }

        val sink = Sink(Flow[String].map(_.toInt)) { implicit b ⇒
          flow ⇒
            import FlowGraph.Implicits._
            flow.outlet ~> Sink(probe)
            flow.inlet
        }

        source1.via(flow).to(sink).run()

        validateProbe(probe, stdRequests, stdResult)
      }
    }

    "used together" should {
      "materialize properly" in {
        val probe = TestSubscriber.manualProbe[Int]()
        val inSource = Source.subscriber[Int]
        val outSink = Sink.publisher[Int]

        val flow = Flow(partialGraph) { implicit b ⇒
          partial ⇒
            import FlowGraph.Implicits._
            (partial.inlet, partial.outlet.map(_.toInt).outlet)
        }

        val source = Source(Flow[Int].map(_.toString), inSource)(Keep.right) { implicit b ⇒
          (flow, src) ⇒
            import FlowGraph.Implicits._
            src.outlet ~> flow.inlet
            flow.outlet
        }

        val sink = Sink(Flow[String].map(_.toInt), outSink)(Keep.right) { implicit b ⇒
          (flow, snk) ⇒
            import FlowGraph.Implicits._
            flow.outlet ~> snk.inlet
            flow.inlet
        }

        val (m1, m2, m3) = FlowGraph.closed(source, flow, sink)(Tuple3.apply) { implicit b ⇒
          (src, f, snk) ⇒
            import FlowGraph.Implicits._
            src.outlet.map(_.toInt) ~> f.inlet
            f.outlet.map(_.toString) ~> snk.inlet
        }.run()

        val subscriber = m1
        val publisher = m3
        source1.runWith(Sink.publisher).subscribe(subscriber)
        publisher.subscribe(probe)

        validateProbe(probe, stdRequests, stdResult)
      }

      "allow connecting source to sink directly" in {
        val probe = TestSubscriber.manualProbe[Int]()
        val inSource = Source.subscriber[Int]
        val outSink = Sink.publisher[Int]

        val source = Source(inSource) { implicit b ⇒
          src ⇒
            src.outlet
        }

        val sink = Sink(outSink) { implicit b ⇒
          snk ⇒
            snk.inlet
        }

        val (m1, m2) = FlowGraph.closed(source, sink)(Keep.both) { implicit b ⇒
          (src, snk) ⇒
            import FlowGraph.Implicits._
            src.outlet ~> snk.inlet
        }.run()

        val subscriber = m1
        val publisher = m2

        source1.runWith(Sink.publisher).subscribe(subscriber)
        publisher.subscribe(probe)

        validateProbe(probe, 4, (0 to 3).toSet)
      }

    }
  }
}
