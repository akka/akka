/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.forkjoin.ThreadLocalRandom.{ current ⇒ random }
import scala.collection.immutable
import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.Utils._
import akka.stream.ActorAttributes
import akka.stream.Supervision

class FlowScanSpec extends AkkaSpec {

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val materializer = ActorMaterializer(settings)

  "A Scan" must {

    def scan(s: Source[Int, Unit], duration: Duration = 5.seconds): immutable.Seq[Int] =
      Await.result(s.scan(0)(_ + _).runFold(immutable.Seq.empty[Int])(_ :+ _), duration)

    "Scan" in assertAllStagesStopped {
      val v = Vector.fill(random.nextInt(100, 1000))(random.nextInt())
      scan(Source(v)) should be(v.scan(0)(_ + _))
    }

    "Scan empty failed" in assertAllStagesStopped {
      val e = new Exception("fail!")
      intercept[Exception](scan(Source.failed[Int](e))) should be theSameInstanceAs (e)
    }

    "Scan empty" in assertAllStagesStopped {
      val v = Vector.empty[Int]
      scan(Source(v)) should be(v.scan(0)(_ + _))
    }

    "emit values promptly" in {
      val f = Source.single(1).concat(Source.lazyEmpty).scan(0)(_ + _).grouped(2).runWith(Sink.head)
      Await.result(f, 1.second) should be(Seq(0, 1))
    }

    "fail properly" in {
      import ActorAttributes._
      val scan = Flow[Int].scan(0) { (old, current) ⇒
        require(current > 0)
        old + current
      }.withAttributes(supervisionStrategy(Supervision.restartingDecider))
      val f = Source(List(1, 3, -1, 5, 7)).via(scan).grouped(1000).runWith(Sink.head)
      Await.result(f, 1.second) should be(Seq(0, 1, 4, 0, 5, 12))
    }
  }
}
