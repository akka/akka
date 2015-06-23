/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.Attributes._
import akka.stream.ActorFlowMaterializer
import akka.stream.ActorFlowMaterializerSettings
import akka.stream.testkit._
import scala.concurrent.duration._
import scala.concurrent.Await

class GraphJunctionAttributesSpec extends AkkaSpec {

  implicit val set = ActorFlowMaterializerSettings(system).withInputBuffer(4, 4)
  implicit val mat = ActorFlowMaterializer(set)

  "A zip" should {

    "take custom inputBuffer settings" in {

      sealed abstract class SlowTick
      case object SlowTick extends SlowTick

      sealed abstract class FastTick
      case object FastTick extends FastTick

      val source = Source[(SlowTick, List[FastTick])]() { implicit b ⇒
        import FlowGraph.Implicits._

        val slow = Source(100.millis, 100.millis, SlowTick)
        val fast = Source(0.seconds, 10.millis, FastTick)

        val zip = b add Zip[SlowTick, List[FastTick]]().withAttributes(inputBuffer(1, 1))

        slow ~> zip.in0
        fast.conflate(tick ⇒ List(tick)) { case (list, tick) ⇒ tick :: list } ~> zip.in1

        zip.out
      }

      val future = source
        .drop(1) // account for prefetch
        .grouped(10)
        .runWith(Sink.head)
      val fastTicks = Await.result(future, 2.seconds).map(_._2.size)

      // Account for the possibility for the zip to act as a buffer of two.
      // If that happens there would be one fast tick for one slow tick in the results.
      // More explanation in #16435
      atLeast(8, fastTicks) shouldBe 10 +- 1
    }
  }

}
