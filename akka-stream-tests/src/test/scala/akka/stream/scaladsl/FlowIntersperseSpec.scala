/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.stream.testkit._
import akka.stream.testkit.scaladsl.{ TestSource, TestSink }
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }
import org.scalatest.concurrent.ScalaFutures
import scala.concurrent.duration._
import akka.testkit.AkkaSpec

class FlowIntersperseSpec extends AkkaSpec {

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val materializer = ActorMaterializer(settings)

  "A Intersperse" must {
    "inject element between existing elements" in {
      val probe = Source(List(1, 2, 3))
        .map(_.toString)
        .intersperse(",")
        .runWith(TestSink.probe)

      probe.expectSubscription()
      probe.toStrict(1.second).mkString("") should ===(List(1, 2, 3).mkString(","))
    }

    "inject element between existing elements, when downstream is fold" in {
      val concated = Source(List(1, 2, 3))
        .map(_.toString)
        .intersperse(",")
        .runFold("")(_ + _)

      concated.futureValue should ===("1,2,3")
    }

    "inject element between existing elements, and surround with []" in {
      val probe = Source(List(1, 2, 3))
        .map(_.toString)
        .intersperse("[", ",", "]")
        .runWith(TestSink.probe)

      probe.toStrict(1.second).mkString("") should ===(List(1, 2, 3).mkString("[", ",", "]"))
    }

    "demonstrate how to prepend only" in {
      val probe = (
        Source.single(">> ") ++ Source(List("1", "2", "3")).intersperse(","))
        .runWith(TestSink.probe)

      probe.toStrict(1.second).mkString("") should ===(List(1, 2, 3).mkString(">> ", ",", ""))
    }

    "surround empty stream with []" in {
      val probe = Source(List())
        .map(_.toString)
        .intersperse("[", ",", "]")
        .runWith(TestSink.probe)

      probe.expectSubscription()
      probe.toStrict(1.second).mkString("") should ===(List().mkString("[", ",", "]"))
    }

    "surround single element stream with []" in {
      val probe = Source(List(1))
        .map(_.toString)
        .intersperse("[", ",", "]")
        .runWith(TestSink.probe)

      probe.expectSubscription()
      probe.toStrict(1.second).mkString("") should ===(List(1).mkString("[", ",", "]"))
    }

    "complete the stage when the Source has been completed" in {
      val (p1, p2) = TestSource.probe[String].intersperse(",").toMat(TestSink.probe[String])(Keep.both).run
      p2.request(10)
      p1.sendNext("a")
        .sendNext("b")
        .sendComplete()
      p2.expectNext("a")
        .expectNext(",")
        .expectNext("b")
        .expectComplete()
    }

    "complete the stage when the Sink has been cancelled" in {
      val (p1, p2) = TestSource.probe[String].intersperse(",").toMat(TestSink.probe[String])(Keep.both).run
      p2.request(10)
      p1.sendNext("a")
        .sendNext("b")
      p2.expectNext("a")
        .expectNext(",")
        .cancel()
      p1.expectCancellation()
    }
  }

}
