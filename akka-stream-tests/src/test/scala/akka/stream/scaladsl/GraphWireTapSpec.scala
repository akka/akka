/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.stream._
import akka.stream.testkit.scaladsl.StreamTestKit._
import akka.stream.testkit._
import akka.stream.testkit.scaladsl.TestSink

class GraphWireTapSpec extends StreamSpec {

  val settings = ActorMaterializerSettings(system).withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val materializer = ActorMaterializer(settings)

  "A wire tap" must {

    "wireTap must broadcast to the tap" in assertAllStagesStopped {
      val tp, mp = TestSink.probe[Int](system)
      val (tps, mps) = Source(1 to 2).wireTapMat(tp)(Keep.right).toMat(mp)(Keep.both).run()
      tps.request(2)
      mps.requestNext(1)
      mps.requestNext(2)
      tps.expectNext(1, 2)
      mps.expectComplete()
      tps.expectComplete()
    }

    "wireTap must drop elements while the tap has no demand, buffering up to one element" in assertAllStagesStopped {
      val tp, mp = TestSink.probe[Int](system)
      val (tps, mps) = Source(1 to 6).wireTapMat(tp)(Keep.right).toMat(mp)(Keep.both).run()
      mps.request(3)
      mps.expectNext(1, 2, 3)
      tps.request(4)
      mps.requestNext(4)
      mps.requestNext(5)
      mps.requestNext(6)
      tps.expectNext(3, 4, 5, 6)
      mps.expectComplete()
      tps.expectComplete()
    }

    "wireTap must cancel if main sink cancels" in assertAllStagesStopped {
      val tp, mp = TestSink.probe[Int](system)
      val (tps, mps) = Source(1 to 6).wireTapMat(tp)(Keep.right).toMat(mp)(Keep.both).run()
      tps.request(6)
      mps.cancel()
      tps.expectComplete()
    }

    "wireTap must continue if tap sink cancels" in assertAllStagesStopped {
      val tp, mp = TestSink.probe[Int](system)
      val (tps, mps) = Source(1 to 6).wireTapMat(tp)(Keep.right).toMat(mp)(Keep.both).run()
      tps.cancel()
      mps.request(6)
      mps.expectNext(1, 2, 3, 4, 5, 6)
      mps.expectComplete()
    }
  }
}
