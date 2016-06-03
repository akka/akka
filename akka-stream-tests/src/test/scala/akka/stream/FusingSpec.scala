/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream

import akka.stream.scaladsl._
import akka.testkit.AkkaSpec
import org.scalactic.ConversionCheckedTripleEquals
import akka.stream.Attributes._
import akka.stream.Fusing.FusedGraph
import scala.annotation.tailrec
import akka.stream.impl.StreamLayout.{ CopiedModule, Module }
import org.scalatest.concurrent.ScalaFutures
import scala.concurrent.duration._
import akka.stream.impl.fusing.GraphInterpreter
import akka.event.BusLogging

class FusingSpec extends AkkaSpec {

  final val Debug = false
  implicit val materializer = ActorMaterializer()

  def graph(async: Boolean) =
    Source.unfold(1)(x ⇒ Some(x → x)).filter(_ % 2 == 1)
      .alsoTo(Flow[Int].fold(0)(_ + _).to(Sink.head.named("otherSink")).addAttributes(if (async) Attributes.asyncBoundary else Attributes.none))
      .via(Flow[Int].fold(1)(_ + _).named("mainSink"))

  def singlePath[S <: Shape, M](fg: FusedGraph[S, M], from: Attribute, to: Attribute): Unit = {
    val starts = fg.module.info.allModules.filter(_.attributes.contains(from))
    starts.size should ===(1)
    val start = starts.head
    val ups = fg.module.info.upstreams
    val owner = fg.module.info.outOwners

    @tailrec def rec(curr: Module): Unit = {
      if (Debug) println(extractName(curr, "unknown"))
      curr match {
        case CopiedModule(_, attributes, copyOf) if (attributes and copyOf.attributes).contains(to) ⇒ ()
        case other if other.attributes.contains(to) ⇒ ()
        case _ ⇒
          val outs = curr.inPorts.map(ups)
          outs.size should ===(1)
          rec(owner(outs.head))
      }
    }

    rec(start)
  }

  "Fusing" must {

    def verify[S <: Shape, M](fused: FusedGraph[S, M], modules: Int, downstreams: Int): Unit = {
      val module = fused.module
      module.subModules.size should ===(modules)
      module.downstreams.size should ===(modules - 1)
      module.info.downstreams.size should be >= downstreams
      module.info.upstreams.size should be >= downstreams
      singlePath(fused, Attributes.Name("mainSink"), Attributes.Name("unfold"))
      singlePath(fused, Attributes.Name("otherSink"), Attributes.Name("unfold"))
    }

    "fuse a moderately complex graph" in {
      val g = graph(false)
      val fused = Fusing.aggressive(g)
      verify(fused, modules = 1, downstreams = 5)
    }

    "not fuse across AsyncBoundary" in {
      val g = graph(true)
      val fused = Fusing.aggressive(g)
      verify(fused, modules = 2, downstreams = 5)
    }

    "not fuse a FusedGraph again" in {
      val g = Fusing.aggressive(graph(false))
      Fusing.aggressive(g) should be theSameInstanceAs g
    }

    "properly fuse a FusedGraph that has been extended (no AsyncBoundary)" in {
      val src = Fusing.aggressive(graph(false))
      val fused = Fusing.aggressive(Source.fromGraph(src).to(Sink.head))
      verify(fused, modules = 1, downstreams = 6)
    }

    "properly fuse a FusedGraph that has been extended (with AsyncBoundary)" in {
      val src = Fusing.aggressive(graph(true))
      val fused = Fusing.aggressive(Source.fromGraph(src).to(Sink.head))
      verify(fused, modules = 2, downstreams = 6)
    }

  }

  "SubFusingActorMaterializer" must {

    "work with asynchronous boundaries in the subflows" in {
      val async = Flow[Int].map(_ * 2).async
      Source(0 to 9)
        .map(_ * 10)
        .flatMapMerge(5, i ⇒ Source(i to (i + 9)).via(async))
        .grouped(1000)
        .runWith(Sink.head)
        .futureValue
        .sorted should ===(0 to 198 by 2)
    }

    "use multiple actors when there are asynchronous boundaries in the subflows (manual)" in {
      def ref = {
        val bus = GraphInterpreter.currentInterpreter.log.asInstanceOf[BusLogging]
        bus.logSource
      }
      val async = Flow[Int].map(x ⇒ { testActor ! ref; x }).async
      Source(0 to 9)
        .map(x ⇒ { testActor ! ref; x })
        .flatMapMerge(5, i ⇒ Source.single(i).via(async))
        .grouped(1000)
        .runWith(Sink.head)
        .futureValue
        .sorted should ===(0 to 9)
      val refs = receiveN(20)
      withClue(s"refs=\n${refs.mkString("\n")}") {
        refs.toSet.size should ===(11) // main flow + 10 subflows
      }
    }

    "use multiple actors when there are asynchronous boundaries in the subflows (combinator)" in {
      def ref = {
        val bus = GraphInterpreter.currentInterpreter.log.asInstanceOf[BusLogging]
        bus.logSource
      }
      val flow = Flow[Int].map(x ⇒ { testActor ! ref; x })
      Source(0 to 9)
        .map(x ⇒ { testActor ! ref; x })
        .flatMapMerge(5, i ⇒ Source.single(i).via(flow.async))
        .grouped(1000)
        .runWith(Sink.head)
        .futureValue
        .sorted should ===(0 to 9)
      val refs = receiveN(20)
      withClue(s"refs=\n${refs.mkString("\n")}") {
        refs.toSet.size should ===(11) // main flow + 10 subflows
      }
    }

  }

}
