/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.scaladsl.OperationAttributes._
import akka.stream.FlowMaterializer
import akka.stream.MaterializerSettings
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.StreamTestKit
import scala.concurrent.duration._
import scala.concurrent.Await

class GraphJunctionAttributesSpec extends AkkaSpec {

  implicit val set = MaterializerSettings(system).withInputBuffer(4, 4)
  implicit val mat = FlowMaterializer(set)

  "A zip" should {

    "take custom inputBuffer settings" in {

      sealed abstract class SlowTick
      case object SlowTick extends SlowTick

      sealed abstract class FastTick
      case object FastTick extends FastTick

      val source = Source[(SlowTick, List[FastTick])]() { implicit b ⇒
        import FlowGraphImplicits._

        val slow = Source(0.seconds, 100.millis, () ⇒ SlowTick)
        val fast = Source(0.seconds, 10.millis, () ⇒ FastTick)
        val sink = UndefinedSink[(SlowTick, List[FastTick])]

        val zip = Zip[SlowTick, List[FastTick]](inputBuffer(1, 1))

        slow ~> zip.left
        fast.conflate(tick ⇒ List(tick)) { case (list, tick) ⇒ tick :: list } ~> zip.right

        zip.out ~> sink

        sink
      }

      val future = source.grouped(10).runWith(Sink.head)

      // FIXME #16435 drop(2) needed because first two SlowTicks get only one FastTick
      Await.result(future, 2.seconds).map(_._2.size).filter(_ == 1).drop(2) should be(Nil)
    }
  }

}
