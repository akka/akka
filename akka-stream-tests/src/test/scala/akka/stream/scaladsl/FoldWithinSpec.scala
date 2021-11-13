/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.stream.impl.fusing.{FoldWith, FoldWithin}
import akka.stream.testkit.{StreamSpec, TestPublisher, TestSubscriber}
import akka.testkit.{AkkaSpec, ExplicitlyTriggeredScheduler}
import com.typesafe.config.ConfigValueFactory

import scala.concurrent.Await
import scala.concurrent.duration._

class FoldWithSpec extends StreamSpec {

  "split aggregator by size" in {

    val stream    = collection.immutable.Seq(1, 2, 3, 4, 5, 6, 7)
    val groupSize = 3
    val result = Source(stream)
      .via(
        new FoldWith[Int, Seq[Int], Seq[Int]](
          seed = i => Seq(i),
          aggregate = (seq, i) => seq :+ i,
          emitOnAgg = seq => seq.size >= groupSize,
          harvest = seq => seq
        )
      )
      .runWith(Sink.collection)

    Await.result(result, 10.seconds) should be (stream.grouped(groupSize).toSeq)

  }

  "split aggregator by size and harvest" in {
    val stream    = collection.immutable.Seq(1, 2, 3, 4, 5, 6, 7)
    val groupSize = 3
    val result = Source(stream)
      .via(
        new FoldWith[Int, Seq[Int], Seq[Int]](
          seed = i => Seq(i),
          aggregate = (seq, i) => seq :+ i,
          emitOnAgg = seq => seq.size >= groupSize,
          harvest = seq => seq :+ -1 // append -1 to output to demonstrate harvest
        )
      )
      .runWith(Sink.collection)


    Await.result(result, 10.seconds) should be (stream.grouped(groupSize).toSeq.map(seq => seq :+ -1))

  }

  "split aggregator by custom weight condition" in {
    val stream = collection.immutable.Seq(1, 2, 3, 4, 5, 6, 7)
    val weight = 10
    // use the value as weight and aggregate
    val result = Source(stream)
      .via(
        new FoldWith[Int, (Seq[Int], Int), Seq[Int]](
          seed = i => (Seq(i), i),
          aggregate = (seqAndWeight, i) => (seqAndWeight._1 :+ i, seqAndWeight._2 + i),
          emitOnAgg = seqAndWeight => seqAndWeight._2 >= weight,
          harvest = seqAndWeight => seqAndWeight._1
        )
      )
      .runWith(Sink.collection)

      Await.result(result, 10.seconds) should be (Seq(Seq(1, 2, 3, 4), Seq(5, 6), Seq(7)))
  }

}

class FoldWithinSpec extends StreamSpec(
  AkkaSpec.testConf.withValue(
    "akka.scheduler.implementation",
    ConfigValueFactory.fromAnyRef( "akka.testkit.ExplicitlyTriggeredScheduler")
  )
) {

  def timePasses(amount: FiniteDuration): Unit = {
    system.scheduler match {
      case ets: ExplicitlyTriggeredScheduler => ets.timePasses(amount)
      case other => throw new Exception(s"expecting ${classOf[ExplicitlyTriggeredScheduler]} but got ${other.getClass}")
    }
  }

  def getSystemTimeMs: Long = {
    system.scheduler match {
      case ets: ExplicitlyTriggeredScheduler => ets.currentTimeEpochMs
      case other => throw new Exception(s"expecting ${classOf[ExplicitlyTriggeredScheduler]} but got ${other.getClass}")
    }
  }

  "split aggregator by gap for slow upstream" in {

    val maxGap = 20.seconds

    val p = TestPublisher.probe[Int]()

    val result = Source.fromPublisher(p)
      .via(
        new FoldWithin[Int, Seq[Int]    , Seq[Int]](
          seed = i => Seq(i),
          aggregate = (seq, i) => seq :+ i,
          emitOnAgg = _ => false,
          harvest = seq => seq,
          maxGap = Some(maxGap), // elements with longer gap will put put to next aggregator
          getSystemTimeMs = getSystemTimeMs
        )
      )
      .runWith(Sink.collection)

    p.sendNext(1)
    timePasses(maxGap/2) // less than maxGap should not cause emit
    p.sendNext(2)
    timePasses(maxGap)

    p.sendNext(3)
    timePasses(maxGap/2) // less than maxGap should not cause emit
    p.sendNext(4)
    timePasses(maxGap)

    p.sendNext(5)
    timePasses(maxGap/2) // less than maxGap should not cause emit
    p.sendNext(6)
    timePasses(maxGap/2) // less than maxGap should not cause emit and does not accumulate
    p.sendNext(7)
    p.sendComplete()

    Await.result(result, 10.seconds) should be (Seq(Seq(1, 2), Seq(3, 4), Seq(5, 6, 7)))

  }

  "split aggregator by total duration" in {
    val maxDuration = 400.seconds

    val p = TestPublisher.probe[Int]()

    val result = Source.fromPublisher(p)
      .via(
        new FoldWithin[Int, Seq[Int], Seq[Int]](
          seed = i => Seq(i),
          aggregate = (seq, i) => seq :+ i,
          emitOnAgg = _ => false,
          harvest = seq => seq,
          maxDuration = Some(maxDuration), // elements with longer gap will put put to next aggregator
          getSystemTimeMs = getSystemTimeMs
        )
      )
      .runWith(Sink.collection)

    p.sendNext(1)
    timePasses(maxDuration/4)
    p.sendNext(2)
    timePasses(maxDuration/4)
    p.sendNext(3)
    timePasses(maxDuration/4)
    p.sendNext(4)
    timePasses(maxDuration/4) // maxDuration will accumulate

    p.sendNext(5)
    p.sendNext(6)
    p.sendNext(7)
    p.sendComplete()

    Await.result(result, 10.seconds) should be (Seq(Seq(1, 2, 3, 4), Seq(5, 6, 7)))

  }

  "down stream back pressure should not miss data on completion without pull on start" in {

    val maxGap = 1.second
    val upstream = TestPublisher.probe[Int]()
    val downstream = TestSubscriber.probe[Seq[Int]]()
    // Note that the cost function set to zero here means the stream will accumulate elements until completed
    Source.fromPublisher(upstream).via(
      new FoldWithin[Int, Seq[Int], Seq[Int]](
        seed = i => Seq(i),
        aggregate = (seq, i) => seq :+ i,
        emitOnAgg = _ => false,
        harvest = seq => seq,
        maxGap = Some(maxGap), // elements with longer gap will put put to next aggregator
        getSystemTimeMs = getSystemTimeMs,
        pullOnStart = false
      )
    ).to(Sink.fromSubscriber(downstream)).run()

    downstream.ensureSubscription()
    upstream.sendNext(1)
    timePasses(maxGap) // this will not cause emit, without eager pull the data does not make to the next stage until downstream request
    upstream.sendNext(2)
    downstream.request(1)
    downstream.expectNoMessage() // no emit
    timePasses(maxGap) // this will cause emit
    downstream.expectNext(Seq(1, 2))
    upstream.sendNext(3)
    upstream.sendNext(4)
    downstream.request(1)
    downstream.expectNoMessage()
    upstream.sendComplete() // flush the pending data
    downstream.expectNext(Seq(3, 4))
    downstream.expectComplete()
  }

  "down stream back pressure should not miss data on completion with pull on start" in {

    val maxGap = 1.second
    val upstream = TestPublisher.probe[Int]()
    val downstream = TestSubscriber.probe[Seq[Int]]()
    // Note that the cost function set to zero here means the stream will accumulate elements until completed
    Source.fromPublisher(upstream).via(
      new FoldWithin[Int, Seq[Int], Seq[Int]](
        seed = i => Seq(i),
        aggregate = (seq, i) => seq :+ i,
        emitOnAgg = _ => false,
        harvest = seq => seq,
        maxGap = Some(maxGap), // elements with longer gap will put put to next aggregator
        getSystemTimeMs = getSystemTimeMs,
        pullOnStart = true
      )
    ).to(Sink.fromSubscriber(downstream)).run()

    downstream.ensureSubscription()
    upstream.sendNext(1)
    timePasses(maxGap) // with pull on start this will cause emit later
    upstream.sendNext(2) // this will be back pressured
    timePasses(maxGap) // this gap will not cause emit due to back pressure
    downstream.request(1)
    downstream.expectNext(Seq(1)) // emit due to the first gap
    upstream.sendNext(3)
    downstream.request(1)
    downstream.expectNoMessage()
    timePasses(maxGap)
    downstream.expectNext(Seq(2, 3))
    upstream.sendNext(4)
    downstream.request(1)
    downstream.expectNoMessage()
    upstream.sendComplete() // flush the pending data
    downstream.expectNext(Seq(4))
    downstream.expectComplete()
  }
}
