/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.forkjoin.ThreadLocalRandom.{ current ⇒ random }

import scala.collection.immutable

import akka.stream.FlowMaterializer
import akka.stream.MaterializerSettings
import akka.stream.testkit.AkkaSpec

class FlowScanSpec extends AkkaSpec {

  val settings = MaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)
    .withFanOutBuffer(initialSize = 1, maxSize = 16)

  implicit val materializer = FlowMaterializer(settings)

  "A Scan" must {

    def scan(s: Source[Int], duration: Duration = 5.seconds): immutable.Seq[Int] =
      Await.result(s.scan(0)(_ + _).fold(immutable.Seq.empty[Int])(_ :+ _), duration)

    "Scan" in {
      val v = Vector.fill(random.nextInt(100, 1000))(random.nextInt())
      scan(Source(v)) should be(v.scan(0)(_ + _))
    }

    "Scan empty failed" in {
      val e = new Exception("fail!")
      intercept[Exception](scan(Source.failed[Int](e))) should be theSameInstanceAs (e)
    }

    "Scan empty" in {
      val v = Vector.empty[Int]
      scan(Source(v)) should be(v.scan(0)(_ + _))
    }
  }
}
