/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream

import akka.stream._
import akka.stream.scaladsl._
import akka.stream.testkit._
import akka.stream.testkit.scaladsl._
import scala.util.Random
import scala.math._
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.collection.immutable
import akka.testkit.TestLatch

class RateTransformationDocSpec extends AkkaSpec {

  type Seq[+A] = immutable.Seq[A]
  val Seq = immutable.Seq

  implicit val materializer = ActorMaterializer()

  "conflate should summarize" in {
    //#conflate-summarize
    val statsFlow = Flow[Double]
      .conflate(Seq(_))(_ :+ _)
      .map { s =>
        val μ = s.sum / s.size
        val se = s.map(x => pow(x - μ, 2))
        val σ = sqrt(se.sum / se.size)
        (σ, μ, s.size)
      }
    //#conflate-summarize

    val fut = Source.fromIterator(() => Iterator.continually(Random.nextGaussian))
      .via(statsFlow)
      .grouped(10)
      .runWith(Sink.head)

    Await.result(fut, 100.millis)
  }

  "conflate should sample" in {
    //#conflate-sample
    val p = 0.01
    val sampleFlow = Flow[Double]
      .conflate(Seq(_)) {
        case (acc, elem) if Random.nextDouble < p => acc :+ elem
        case (acc, _)                             => acc
      }
      .mapConcat(identity)
    //#conflate-sample

    val fut = Source(1 to 1000)
      .map(_.toDouble)
      .via(sampleFlow)
      .runWith(Sink.fold(Seq.empty[Double])(_ :+ _))

    val count = Await.result(fut, 1000.millis).size
  }

  "expand should repeat last" in {
    //#expand-last
    val lastFlow = Flow[Double]
      .expand(identity)(s => (s, s))
    //#expand-last

    val (probe, fut) = TestSource.probe[Double]
      .via(lastFlow)
      .grouped(10)
      .toMat(Sink.head)(Keep.both)
      .run()

    probe.sendNext(1.0)
    val expanded = Await.result(fut, 100.millis)
    expanded.size shouldBe 10
    expanded.sum shouldBe 10
  }

  "expand should track drift" in {
    //#expand-drift
    val driftFlow = Flow[Double]
      .expand((_, 0)) {
        case (lastElement, drift) => ((lastElement, drift), (lastElement, drift + 1))
      }
    //#expand-drift
    val latch = TestLatch(2)
    val realDriftFlow = Flow[Double]
      .expand(d => { latch.countDown(); (d, 0) }) {
        case (lastElement, drift) => ((lastElement, drift), (lastElement, drift + 1))
      }

    val (pub, sub) = TestSource.probe[Double]
      .via(realDriftFlow)
      .toMat(TestSink.probe[(Double, Int)])(Keep.both)
      .run()

    sub.request(1)
    pub.sendNext(1.0)
    sub.expectNext((1.0, 0))

    sub.requestNext((1.0, 1))
    sub.requestNext((1.0, 2))

    pub.sendNext(2.0)
    Await.ready(latch, 1.second)
    sub.requestNext((2.0, 0))
  }

}
