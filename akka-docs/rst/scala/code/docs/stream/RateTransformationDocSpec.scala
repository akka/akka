/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
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
import akka.testkit.{ AkkaSpec, TestLatch }

class RateTransformationDocSpec extends AkkaSpec {

  type Seq[+A] = immutable.Seq[A]
  val Seq = immutable.Seq

  implicit val materializer = ActorMaterializer()

  "conflate should summarize" in {
    //#conflate-summarize
    val statsFlow = Flow[Double]
      .conflateWithSeed(Seq(_))(_ :+ _)
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
      .conflateWithSeed(Seq(_)) {
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
      .expand(Iterator.continually(_))
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
      .expand(i => Iterator.from(0).map(i -> _))
    //#expand-drift
    val latch = TestLatch(2)
    val realDriftFlow = Flow[Double]
      .expand(d => { latch.countDown(); Iterator.from(0).map(d -> _) })

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
