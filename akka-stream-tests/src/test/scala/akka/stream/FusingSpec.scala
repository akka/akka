/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.stream._
import akka.stream.scaladsl._
import akka.stream.testkit.AkkaSpec
import org.scalactic.ConversionCheckedTripleEquals

class FusingSpec extends AkkaSpec with ConversionCheckedTripleEquals {

  implicit val materializer = ActorMaterializer()

  "Fusing" must {

    "fuse a moderately complex graph" in {
      val g = Source.unfoldInf(1)(x ⇒ (x, x)).filter(_ % 2 == 1).alsoTo(Sink.fold(0)(_ + _)).to(Sink.fold(1)(_ + _))
      val fused = Fusing.aggressive(g)
      val module = fused.module
      module.subModules.size should ===(1)
      module.info.downstreams.size should be > 5
      module.info.upstreams.size should be > 5
    }

    "not fuse across AsyncBoundary" in {
      val g =
        Source.unfoldInf(1)(x ⇒ (x, x)).filter(_ % 2 == 1)
          .alsoTo(Sink.fold(0)(_ + (_: Int)).addAttributes(Attributes.asyncBoundary))
          .to(Sink.fold(1)(_ + _))
      val fused = Fusing.aggressive(g)
      val module = fused.module
      module.subModules.size should ===(2)
      module.info.downstreams.size should be > 5
      module.info.upstreams.size should be > 5
    }

  }

}
