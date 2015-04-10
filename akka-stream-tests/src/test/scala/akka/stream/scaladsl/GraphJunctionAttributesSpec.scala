/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.OperationAttributes._
import akka.stream.ActorFlowMaterializer
import akka.stream.ActorFlowMaterializerSettings
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.StreamTestKit
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

        val slow = Source(0.seconds, 100.millis, SlowTick)
        val fast = Source(0.seconds, 10.millis, FastTick)

        val zip = b add Zip[SlowTick, List[FastTick]](inputBuffer(1, 1))

        slow ~> zip.in0
        fast.conflate(tick ⇒ List(tick)) { case (list, tick) ⇒ tick :: list } ~> zip.in1

        zip.out
      }

      val future = source.grouped(10).runWith(Sink.head)

      // FIXME #16435 drop(2) needed because first two SlowTicks get only one FastTick
      Await.result(future, 2.seconds).map(_._2.size).filter(_ == 1).drop(2) should be(Nil)
    }
  }

}
