/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import akka.stream.testkit._
import akka.stream._
import org.reactivestreams.Subscriber

object GraphFlowSpec {
  val source1 = Source(0 to 3)

  val partialGraph = FlowGraph.create() { implicit b ⇒
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

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val materializer = ActorMaterializer(settings)

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

        val flow = Flow.fromGraph(FlowGraph.create(partialGraph) { implicit b ⇒
          partial ⇒
            import FlowGraph.Implicits._
            FlowShape(partial.inlet, partial.outlet.map(_.toInt).outlet)
        })

        source1.via(flow).to(Sink(probe)).run()

        validateProbe(probe, stdRequests, stdResult)
      }

      "be transformable with a Pipe" in {
        val probe = TestSubscriber.manualProbe[Int]()

        val flow = Flow.fromGraph(FlowGraph.create(partialGraph) { implicit b ⇒
          partial ⇒ FlowShape(partial.inlet, partial.outlet)
        })

        source1.via(flow).map(_.toInt).to(Sink(probe)).run()

        validateProbe(probe, stdRequests, stdResult)
      }

      "work with another GraphFlow" in {
        val probe = TestSubscriber.manualProbe[Int]()

        val flow1 = Flow.fromGraph(FlowGraph.create(partialGraph) { implicit b ⇒
          partial ⇒
            FlowShape(partial.inlet, partial.outlet)
        })

        val flow2 = Flow.fromGraph(FlowGraph.create(Flow[String].map(_.toInt)) { implicit b ⇒
          importFlow ⇒
            FlowShape(importFlow.inlet, importFlow.outlet)
        })

        source1.via(flow1).via(flow2).to(Sink(probe)).run()

        validateProbe(probe, stdRequests, stdResult)
      }

      "be reusable multiple times" in {
        val probe = TestSubscriber.manualProbe[Int]()

        val flow = Flow.fromGraph(FlowGraph.create(Flow[Int].map(_ * 2)) { implicit b ⇒
          importFlow ⇒ FlowShape(importFlow.inlet, importFlow.outlet)
        })

        RunnableGraph.fromGraph(FlowGraph.create() { implicit b ⇒
          import FlowGraph.Implicits._
          Source(1 to 5) ~> flow ~> flow ~> Sink(probe)
          ClosedShape
        }).run()

        validateProbe(probe, 5, Set(4, 8, 12, 16, 20))
      }
    }

    "turned into sources" should {
      "work with a Sink" in {
        val probe = TestSubscriber.manualProbe[Int]()

        val source = Source.fromGraph(FlowGraph.create(partialGraph) { implicit b ⇒
          partial ⇒
            import FlowGraph.Implicits._
            source1 ~> partial.inlet
            SourceShape(partial.outlet.map(_.toInt).outlet)
        })

        source.to(Sink(probe)).run()

        validateProbe(probe, stdRequests, stdResult)
      }

      "work with a Sink when having KeyedSource inside" in {
        val probe = TestSubscriber.manualProbe[Int]()
        val source = Source.subscriber[Int]
        val mm: Subscriber[Int] = source.to(Sink(probe)).run()
        source1.to(Sink(mm)).run()

        validateProbe(probe, 4, (0 to 3).toSet)
      }

      "be transformable with a Pipe" in {

        val probe = TestSubscriber.manualProbe[Int]()

        val source = Source.fromGraph(FlowGraph.create(partialGraph) { implicit b ⇒
          partial ⇒
            import FlowGraph.Implicits._
            source1 ~> partial.inlet
            SourceShape(partial.outlet)
        })

        source.map(_.toInt).to(Sink(probe)).run()

        validateProbe(probe, stdRequests, stdResult)
      }

      "work with an GraphFlow" in {
        val probe = TestSubscriber.manualProbe[Int]()

        val source = Source.fromGraph(FlowGraph.create(partialGraph) { implicit b ⇒
          partial ⇒
            import FlowGraph.Implicits._
            source1 ~> partial.inlet
            SourceShape(partial.outlet)
        })

        val flow = Flow.fromGraph(FlowGraph.create(Flow[String].map(_.toInt)) { implicit b ⇒
          importFlow ⇒
            FlowShape(importFlow.inlet, importFlow.outlet)
        })

        source.via(flow).to(Sink(probe)).run()

        validateProbe(probe, stdRequests, stdResult)
      }

      "be reusable multiple times" in {
        val probe = TestSubscriber.manualProbe[Int]()

        val source = Source.fromGraph(FlowGraph.create(Source(1 to 5)) { implicit b ⇒
          s ⇒
            import FlowGraph.Implicits._
            SourceShape(s.outlet.map(_ * 2).outlet)
        })

        RunnableGraph.fromGraph(FlowGraph.create(source, source)(Keep.both) { implicit b ⇒
          (s1, s2) ⇒
            import FlowGraph.Implicits._
            val merge = b.add(Merge[Int](2))
            s1.outlet ~> merge.in(0)
            merge.out ~> Sink(probe)
            s2.outlet.map(_ * 10) ~> merge.in(1)
            ClosedShape
        }).run()

        validateProbe(probe, 10, Set(2, 4, 6, 8, 10, 20, 40, 60, 80, 100))
      }
    }

    "turned into sinks" should {
      "work with a Source" in {
        val probe = TestSubscriber.manualProbe[Int]()

        val sink = Sink.fromGraph(FlowGraph.create(partialGraph) { implicit b ⇒
          partial ⇒
            import FlowGraph.Implicits._
            partial.outlet.map(_.toInt) ~> Sink(probe)
            SinkShape(partial.inlet)
        })

        source1.to(sink).run()

        validateProbe(probe, stdRequests, stdResult)
      }

      "work with a Source when having KeyedSink inside" in {
        val probe = TestSubscriber.manualProbe[Int]()
        val pubSink = Sink.publisher[Int](false)

        val sink = Sink.fromGraph(FlowGraph.create(pubSink) { implicit b ⇒
          p ⇒ SinkShape(p.inlet)
        })

        val mm = source1.runWith(sink)
        Source(mm).to(Sink(probe)).run()

        validateProbe(probe, 4, (0 to 3).toSet)
      }

      "be transformable with a Pipe" in {
        val probe = TestSubscriber.manualProbe[Int]()

        val sink = Sink.fromGraph(FlowGraph.create(partialGraph, Flow[String].map(_.toInt))(Keep.both) { implicit b ⇒
          (partial, flow) ⇒
            import FlowGraph.Implicits._
            flow.outlet ~> partial.inlet
            partial.outlet.map(_.toInt) ~> Sink(probe)
            SinkShape(flow.inlet)
        })

        val iSink = Flow[Int].map(_.toString).to(sink)
        source1.to(iSink).run()

        validateProbe(probe, stdRequests, stdResult)
      }

      "work with a GraphFlow" in {

        val probe = TestSubscriber.manualProbe[Int]()

        val flow = Flow.fromGraph(FlowGraph.create(partialGraph) { implicit b ⇒
          partial ⇒
            FlowShape(partial.inlet, partial.outlet)
        })

        val sink = Sink.fromGraph(FlowGraph.create(Flow[String].map(_.toInt)) { implicit b ⇒
          flow ⇒
            import FlowGraph.Implicits._
            flow.outlet ~> Sink(probe)
            SinkShape(flow.inlet)
        })

        source1.via(flow).to(sink).run()

        validateProbe(probe, stdRequests, stdResult)
      }
    }

    "used together" should {
      "materialize properly" in {
        val probe = TestSubscriber.manualProbe[Int]()
        val inSource = Source.subscriber[Int]
        val outSink = Sink.publisher[Int](false)

        val flow = Flow.fromGraph(FlowGraph.create(partialGraph) { implicit b ⇒
          partial ⇒
            import FlowGraph.Implicits._
            FlowShape(partial.inlet, partial.outlet.map(_.toInt).outlet)
        })

        val source = Source.fromGraph(FlowGraph.create(Flow[Int].map(_.toString), inSource)(Keep.right) { implicit b ⇒
          (flow, src) ⇒
            import FlowGraph.Implicits._
            src.outlet ~> flow.inlet
            SourceShape(flow.outlet)
        })

        val sink = Sink.fromGraph(FlowGraph.create(Flow[String].map(_.toInt), outSink)(Keep.right) { implicit b ⇒
          (flow, snk) ⇒
            import FlowGraph.Implicits._
            flow.outlet ~> snk.inlet
            SinkShape(flow.inlet)
        })

        val (m1, m2, m3) = RunnableGraph.fromGraph(FlowGraph.create(source, flow, sink)(Tuple3.apply) { implicit b ⇒
          (src, f, snk) ⇒
            import FlowGraph.Implicits._
            src.outlet.map(_.toInt) ~> f.inlet
            f.outlet.map(_.toString) ~> snk.inlet
            ClosedShape
        }).run()

        val subscriber = m1
        val publisher = m3
        source1.runWith(Sink.publisher(false)).subscribe(subscriber)
        publisher.subscribe(probe)

        validateProbe(probe, stdRequests, stdResult)
      }

      "allow connecting source to sink directly" in {
        val probe = TestSubscriber.manualProbe[Int]()
        val inSource = Source.subscriber[Int]
        val outSink = Sink.publisher[Int](false)

        val source = Source.fromGraph(FlowGraph.create(inSource) { implicit b ⇒
          src ⇒
            SourceShape(src.outlet)
        })

        val sink = Sink.fromGraph(FlowGraph.create(outSink) { implicit b ⇒
          snk ⇒
            SinkShape(snk.inlet)
        })

        val (m1, m2) = RunnableGraph.fromGraph(FlowGraph.create(source, sink)(Keep.both) { implicit b ⇒
          (src, snk) ⇒
            import FlowGraph.Implicits._
            src.outlet ~> snk.inlet
            ClosedShape
        }).run()

        val subscriber = m1
        val publisher = m2

        source1.runWith(Sink.publisher(false)).subscribe(subscriber)
        publisher.subscribe(probe)

        validateProbe(probe, 4, (0 to 3).toSet)
      }

    }
  }
}
