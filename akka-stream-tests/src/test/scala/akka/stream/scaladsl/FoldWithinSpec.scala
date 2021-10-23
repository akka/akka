/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.stream.impl.fusing.FoldWithin
import akka.stream.testkit.StreamSpec

import scala.concurrent.Await
import scala.concurrent.duration._

class FoldWithinSpec extends StreamSpec {

  "split aggregator by size" in {
    val stream    = collection.immutable.Seq(1, 2, 3, 4, 5, 6, 7)
    val groupSize = 3
    val result = Source(stream)
      .via(
        new FoldWithin[Int, Seq[Int], Seq[Int]](
          seed = i => Seq(i),
          aggregate = (seq, i) => seq :+ i,
          emitReady = seq => seq.size >= groupSize,
          harvest = seq => seq
        )
      )
      .runWith(Sink.collection)

    assert(
      Await.result(result, 10.seconds) == stream.grouped(groupSize).toSeq
    )
  }

  "split aggregator by size and harvest" in {
    val stream    = collection.immutable.Seq(1, 2, 3, 4, 5, 6, 7)
    val groupSize = 3
    val result = Source(stream)
      .via(
        new FoldWithin[Int, Seq[Int], Seq[Int]](
          seed = i => Seq(i),
          aggregate = (seq, i) => seq :+ i,
          emitReady = seq => seq.size >= groupSize,
          harvest = seq => seq :+ -1 // append -1 to output to demonstrate harvest
        )
      )
      .runWith(Sink.collection)

    assert(
      Await.result(result, 10.seconds) == stream.grouped(groupSize).toSeq.map(seq => seq :+ -1)
    )
  }

  "split aggregator by custom weight condition" in {
    val stream = collection.immutable.Seq(1, 2, 3, 4, 5, 6, 7)
    val weight = 10
    // use the value as weight and aggregate
    val result = Source(stream)
      .via(
        new FoldWithin[Int, (Seq[Int], Int), Seq[Int]](
          seed = i => (Seq(i), i),
          aggregate = (seqAndWeight, i) => (seqAndWeight._1 :+ i, seqAndWeight._2 + i),
          emitReady = seqAndWeight => seqAndWeight._2 >= weight,
          harvest = seqAndWeight => seqAndWeight._1
        )
      )
      .runWith(Sink.collection)

    assert(
      Await.result(result, 10.seconds) ==
        Seq(Seq(1, 2, 3, 4), Seq(5, 6), Seq(7))
    )
  }

  "split aggregator by gap for slow upstream" in {
    val stream = collection.immutable.Seq(
      (1, 10),
      (2, 10),
      (3, 100),
      (4, 10),
      (5, 100),
      (6, 10),
      (7, 10)
    )

    val maxGap = 20.millis

    val result = Source(stream)
      .map {
        case (i, gap) =>
          Thread.sleep(gap)
          i
      }
      .async // must use async to not let Thread.sleep block the next stage
      .via(
        new FoldWithin[Int, (Seq[Int]), Seq[Int]](
          seed = i => Seq(i),
          aggregate = (seq, i) => seq :+ i,
          emitReady = _ => false,
          harvest = seq => seq,
          maxGap = Some(maxGap) // elements with longer gap will put put to next aggregator
        )
      )
      .runWith(Sink.collection)

    assert(
      Await.result(result, 10.seconds) ==
        Seq(Seq(1, 2), Seq(3, 4), Seq(5, 6, 7))
    )
  }

  "split aggregator by total duration" in {
    val stream = collection.immutable.Seq(
      (1, 100),
      (2, 100),
      (3, 100),
      (4, 100),
      (5, 300), // exceeds total duration before 5
      (6, 100),
      (7, 100)
    )

    val maxDuration = 400.millis

    val result = Source(stream)
      .map {
        case (i, gap) =>
          Thread.sleep(gap)
          i
      }
      .async // must use async to not let Thread.sleep block the next stage
      .via(
        new FoldWithin[Int, Seq[Int], Seq[Int]](
          seed = i => Seq(i),
          aggregate = (seq, i) => seq :+ i,
          emitReady = _ => false,
          harvest = seq => seq,
          maxDuration = Some(maxDuration) // elements with longer gap will put put to next aggregator
        )
      )
      .runWith(Sink.collection)

    assert(
      Await.result(result, 10.seconds) ==
        Seq(Seq(1, 2, 3, 4), Seq(5, 6, 7))
    )
  }

}
