/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.scaladsl.OperationAttributes._
import akka.stream.{ FlowMaterializer, MaterializerSettings }
import akka.stream.impl.{ Optimizations, ActorBasedFlowMaterializer }
import akka.stream.testkit.AkkaSpec
import akka.testkit._

import scala.concurrent.duration._
import scala.concurrent.Await

class OptimizingActorBasedFlowMaterializerSpec extends AkkaSpec with ImplicitSender {

  "ActorBasedFlowMaterializer" must {
    //FIXME Add more and meaningful tests to verify that optimizations occur and have the same semantics as the non-optimized code
    "optimize filter + map" in {
      implicit val mat = FlowMaterializer().asInstanceOf[ActorBasedFlowMaterializer].copy(optimizations = Optimizations.all)
      val f = Source(1 to 100).
        drop(4).
        drop(5).
        section(name("identity"))(_.transform(() ⇒ FlowOps.identityStage)).
        filter(_ % 2 == 0).
        map(_ * 2).
        map(identity).
        take(20).
        take(10).
        drop(5).
        fold(0)(_ + _)

      val expected = (1 to 100).
        drop(9).
        filter(_ % 2 == 0).
        map(_ * 2).
        take(10).
        drop(5).
        fold(0)(_ + _)

      Await.result(f, 5.seconds) should be(expected)
    }

    "optimize map + map" in {
      implicit val mat = FlowMaterializer().asInstanceOf[ActorBasedFlowMaterializer].copy(optimizations = Optimizations.all)

      val fl = Source(1 to 100).map(_ + 2).map(_ * 2).fold(0)(_ + _)
      val expected = (1 to 100).map(_ + 2).map(_ * 2).fold(0)(_ + _)

      Await.result(fl, 5.seconds) should be(expected)
    }
  }
}
