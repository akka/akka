/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.testkit._
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.duration._

class FlowIntersperseSpec extends AkkaSpec with ScalaFutures {

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
  }

}
