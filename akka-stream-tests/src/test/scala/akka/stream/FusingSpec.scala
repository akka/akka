/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.stream._
import akka.stream.scaladsl._
import akka.stream.testkit.AkkaSpec
import org.scalactic.ConversionCheckedTripleEquals
import akka.stream.Attributes._
import akka.stream.Fusing.FusedGraph
import scala.annotation.tailrec
import akka.stream.impl.StreamLayout.Module

class FusingSpec extends AkkaSpec with ConversionCheckedTripleEquals {

  final val Debug = false
  implicit val materializer = ActorMaterializer()

  def graph(async: Boolean) =
    Source.unfoldInf(1)(x ⇒ (x, x)).filter(_ % 2 == 1)
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
      if (curr.attributes.contains(to)) () // done
      else {
        val outs = curr.inPorts.map(ups)
        outs.size should ===(1)
        val out = outs.head
        val next = owner(out)
        rec(next)
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
      singlePath(fused, Attributes.Name("mainSink"), Attributes.Name("unfoldInf"))
      singlePath(fused, Attributes.Name("otherSink"), Attributes.Name("unfoldInf"))
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

}
