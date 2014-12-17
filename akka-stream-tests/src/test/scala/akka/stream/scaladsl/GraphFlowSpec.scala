/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.FlowMaterializer
import akka.stream.MaterializerSettings
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.StreamTestKit.SubscriberProbe
import akka.stream.testkit.StreamTestKit

object GraphFlowSpec {
  val source1 = Source(0 to 3)
  val inMerge = Merge[Int]
  val outMerge = Merge[String]

  val partialGraph = PartialFlowGraph { implicit b ⇒
    import FlowGraphImplicits._
    val source2 = Source(4 to 9)
    val source3 = Source.empty[Int]
    val source4 = Source.empty[String]
    val m2 = Merge[Int]

    inMerge ~> Flow[Int].map(_ * 2) ~> m2 ~> Flow[Int].map(_ / 2).map(i ⇒ (i + 1).toString) ~> outMerge
    source2 ~> inMerge
    source3 ~> m2
    source4 ~> outMerge
  }

  val stdRequests = 10
  val stdResult = Set(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
}

class GraphFlowSpec extends AkkaSpec {

  import GraphFlowSpec._

  val settings = MaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val materializer = FlowMaterializer(settings)

  def validateProbe(probe: SubscriberProbe[Int], requests: Int, result: Set[Int]): Unit = {
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
        val in = UndefinedSource[Int]
        val out = UndefinedSink[Int]
        val probe = StreamTestKit.SubscriberProbe[Int]()

        val flow = Flow(partialGraph) { implicit b ⇒
          import FlowGraphImplicits._
          in ~> inMerge
          outMerge ~> Flow[String].map(_.toInt) ~> out
          in -> out
        }

        source1.via(flow).to(Sink(probe)).run()

        validateProbe(probe, stdRequests, stdResult)
      }

      "be transformable with a Pipe" in {
        val in = UndefinedSource[Int]
        val out = UndefinedSink[String]

        val probe = StreamTestKit.SubscriberProbe[Int]()

        val flow = Flow[Int, String](partialGraph) { implicit b ⇒
          import FlowGraphImplicits._
          in ~> inMerge
          outMerge ~> out
          in -> out
        }

        source1.via(flow).map(_.toInt).to(Sink(probe)).run()

        validateProbe(probe, stdRequests, stdResult)
      }

      "work with another GraphFlow" in {
        val in1 = UndefinedSource[Int]
        val out1 = UndefinedSink[String]

        val in2 = UndefinedSource[String]
        val out2 = UndefinedSink[Int]

        val probe = StreamTestKit.SubscriberProbe[Int]()

        val flow1 = Flow(partialGraph) { implicit b ⇒
          import FlowGraphImplicits._
          in1 ~> inMerge
          outMerge ~> out1
          in1 -> out1
        }

        val flow2 = Flow() { implicit b ⇒
          import FlowGraphImplicits._
          in2 ~> Flow[String].map(_.toInt) ~> out2
          in2 -> out2
        }

        source1.via(flow1).via(flow2).to(Sink(probe)).run()

        validateProbe(probe, stdRequests, stdResult)
      }

      "be reusable multiple times" in {
        val in = UndefinedSource[Int]
        val out = UndefinedSink[Int]
        val probe = StreamTestKit.SubscriberProbe[Int]()

        val flow = Flow() { implicit b ⇒
          import FlowGraphImplicits._
          in ~> Flow[Int].map(_ * 2) ~> out
          in -> out
        }

        FlowGraph { implicit b ⇒
          import FlowGraphImplicits._
          Source(1 to 5) ~> flow ~> flow ~> Sink(probe)
        }.run()

        validateProbe(probe, 5, Set(4, 8, 12, 16, 20))
      }
    }

    "turned into sources" should {
      "work with a Sink" in {
        val out = UndefinedSink[Int]
        val probe = StreamTestKit.SubscriberProbe[Int]()

        val source = Source(partialGraph) { implicit b ⇒
          import FlowGraphImplicits._
          source1 ~> inMerge
          outMerge ~> Flow[String].map(_.toInt) ~> out
          out
        }

        source.to(Sink(probe)).run()

        validateProbe(probe, stdRequests, stdResult)
      }

      "work with a Sink when having KeyedSource inside" in {
        val out = UndefinedSink[Int]
        val probe = StreamTestKit.SubscriberProbe[Int]()
        val subSource = Source.subscriber[Int]

        val source = Source[Int]() { implicit b ⇒
          import FlowGraphImplicits._
          subSource ~> out
          out
        }

        val mm = source.to(Sink(probe)).run()
        source1.to(Sink(mm.get(subSource))).run()

        validateProbe(probe, 4, (0 to 3).toSet)
      }

      "be transformable with a Pipe" in {
        val out = UndefinedSink[String]

        val probe = StreamTestKit.SubscriberProbe[Int]()

        val source = Source[String](partialGraph) { implicit b ⇒
          import FlowGraphImplicits._
          source1 ~> inMerge
          outMerge ~> out
          out
        }

        source.map(_.toInt).to(Sink(probe)).run()

        validateProbe(probe, stdRequests, stdResult)
      }

      "work with an GraphFlow" in {
        val out1 = UndefinedSink[String]

        val in2 = UndefinedSource[String]
        val out2 = UndefinedSink[Int]

        val probe = StreamTestKit.SubscriberProbe[Int]()

        val source = Source(partialGraph) { implicit b ⇒
          import FlowGraphImplicits._
          source1 ~> inMerge
          outMerge ~> out1
          out1
        }

        val flow = Flow() { implicit b ⇒
          import FlowGraphImplicits._
          in2 ~> Flow[String].map(_.toInt) ~> out2
          in2 -> out2
        }

        source.via(flow).to(Sink(probe)).run()

        validateProbe(probe, stdRequests, stdResult)
      }

      "be reusable multiple times" in {
        val out = UndefinedSink[Int]
        val probe = StreamTestKit.SubscriberProbe[Int]()

        val source1 = Source[Int]() { implicit b ⇒
          import FlowGraphImplicits._
          Source(1 to 5) ~> Flow[Int].map(_ * 2) ~> out
          out
        }
        val source2 = Source[Int]() { implicit b ⇒
          import FlowGraphImplicits._
          Source(1 to 5) ~> Flow[Int].map(_ * 2) ~> out
          out
        }

        FlowGraph { implicit b ⇒
          import FlowGraphImplicits._
          val merge = Merge[Int]
          source1 ~> merge ~> Sink(probe)
          source2 ~> Flow[Int].map(_ * 10) ~> merge
        }.run()

        validateProbe(probe, 10, Set(2, 4, 6, 8, 10, 20, 40, 60, 80, 100))
      }
    }

    "turned into sinks" should {
      "work with a Source" in {
        val in = UndefinedSource[Int]
        val probe = StreamTestKit.SubscriberProbe[Int]()

        val sink = Sink(partialGraph) { implicit b ⇒
          import FlowGraphImplicits._
          in ~> inMerge
          outMerge ~> Flow[String].map(_.toInt) ~> Sink(probe)
          in
        }

        source1.to(sink).run()

        validateProbe(probe, stdRequests, stdResult)
      }

      "work with a Source when having KeyedSink inside" in {
        val in = UndefinedSource[Int]
        val probe = StreamTestKit.SubscriberProbe[Int]()
        val pubSink = Sink.publisher[Int]

        val sink = Sink[Int]() { implicit b ⇒
          import FlowGraphImplicits._
          in ~> pubSink
          in
        }

        val mm = source1.to(sink).run()
        Source(mm.get(pubSink)).to(Sink(probe)).run()

        validateProbe(probe, 4, (0 to 3).toSet)
      }

      "be transformable with a Pipe" in {
        val in = UndefinedSource[String]

        val probe = StreamTestKit.SubscriberProbe[Int]()

        val sink = Sink(partialGraph) { implicit b ⇒
          import FlowGraphImplicits._
          in ~> Flow[String].map(_.toInt) ~> inMerge
          outMerge ~> Flow[String].map(_.toInt) ~> Sink(probe)
          in
        }

        val iSink = Flow[Int].map(_.toString).to(sink)
        source1.to(iSink).run()

        validateProbe(probe, stdRequests, stdResult)
      }

      "work with a GraphFlow" in {
        val in1 = UndefinedSource[Int]
        val out1 = UndefinedSink[String]

        val in2 = UndefinedSource[String]

        val probe = StreamTestKit.SubscriberProbe[Int]()

        val flow = Flow(partialGraph) { implicit b ⇒
          import FlowGraphImplicits._
          in1 ~> inMerge
          outMerge ~> out1
          in1 -> out1
        }

        val sink = Sink() { implicit b ⇒
          import FlowGraphImplicits._
          in2 ~> Flow[String].map(_.toInt) ~> Sink(probe)
          in2
        }

        source1.via(flow).to(sink).run()

        validateProbe(probe, stdRequests, stdResult)
      }
    }

    "used together" should {
      "materialize properly" in {
        val probe = StreamTestKit.SubscriberProbe[Int]()
        val inSource = Source.subscriber[Int]
        val outSink = Sink.publisher[Int]

        val flow = Flow(partialGraph) { implicit b ⇒
          import FlowGraphImplicits._
          val in = UndefinedSource[Int]
          val out = UndefinedSink[Int]
          in ~> inMerge
          outMerge ~> Flow[String].map(_.toInt) ~> out
          in -> out
        }

        val source = Source[String]() { implicit b ⇒
          import FlowGraphImplicits._
          val out = UndefinedSink[String]
          inSource ~> Flow[Int].map(_.toString) ~> out
          out
        }

        val sink = Sink() { implicit b ⇒
          import FlowGraphImplicits._
          val in = UndefinedSource[String]
          in ~> Flow[String].map(_.toInt) ~> outSink
          in
        }

        val mm = FlowGraph { implicit b ⇒
          import FlowGraphImplicits._
          source ~> Flow[String].map(_.toInt) ~> flow ~> Flow[Int].map(_.toString) ~> sink
        }.run()

        val subscriber = mm.get(inSource)
        val publisher = mm.get(outSink)
        source1.runWith(Sink.publisher).subscribe(subscriber)
        publisher.subscribe(probe)

        validateProbe(probe, stdRequests, stdResult)
      }

      "allow connecting source to sink directly" in {
        val probe = StreamTestKit.SubscriberProbe[Int]()
        val inSource = Source.subscriber[Int]
        val outSink = Sink.publisher[Int]

        val source = Source[Int]() { implicit b ⇒
          import FlowGraphImplicits._
          val out = UndefinedSink[Int]
          inSource ~> out
          out
        }

        val sink = Sink[Int]() { implicit b ⇒
          import FlowGraphImplicits._
          val in = UndefinedSource[Int]
          in ~> outSink
          in
        }

        val mm = FlowGraph { implicit b ⇒
          import FlowGraphImplicits._
          val broadcast = Broadcast[Int]
          source ~> sink
        }.run()

        val subscriber = mm.get(inSource)
        val publisher = mm.get(outSink)

        source1.runWith(Sink.publisher).subscribe(subscriber)
        publisher.subscribe(probe)

        validateProbe(probe, 4, (0 to 3).toSet)
      }

    }
  }
}
