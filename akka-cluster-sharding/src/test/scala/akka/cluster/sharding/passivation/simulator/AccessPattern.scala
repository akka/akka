/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.passivation.simulator

import akka.NotUsed
import akka.cluster.sharding.ShardRegion.EntityId
import akka.stream.scaladsl._
import akka.util.ByteString

import java.nio.file.Paths

trait AccessPattern {
  def entityIds: Source[EntityId, NotUsed]
}

abstract class SyntheticGenerator(events: Int) extends AccessPattern {
  protected def nextValue(event: Int): Long

  protected def generateEntityIds: Source[Long, NotUsed] = Source.fromIterator(() => Iterator.from(1)).map(nextValue)

  override def entityIds: Source[EntityId, NotUsed] = generateEntityIds.take(events).map(_.toString)
}

object SyntheticGenerator {
  import site.ycsb.generator._

  /**
   * Generate a sequence of unique id events.
   */
  final class Sequence(start: Long, events: Int) extends SyntheticGenerator(events) {
    private val generator = new CounterGenerator(start)
    override protected def nextValue(event: Int): Long = generator.nextValue()
  }

  /**
   * Generate a looping sequence of id events.
   */
  final class Loop(start: Long, end: Long, events: Int) extends SyntheticGenerator(events) {
    private val generator = new SequentialGenerator(start, end)
    override protected def nextValue(event: Int): Long = generator.nextValue().longValue
  }

  /**
   * Generate id events randomly using a uniform distribution, from the inclusive range min to max.
   */
  final class Uniform(min: Long, max: Long, events: Int) extends SyntheticGenerator(events) {
    private val generator = new UniformLongGenerator(min, max)
    override protected def nextValue(event: Int): Long = generator.nextValue()
  }

  /**
   * Generate id events based on an exponential distribution given the mean (expected value) of the distribution.
   */
  final class Exponential(mean: Double, events: Int) extends SyntheticGenerator(events) {
    private val generator = new ExponentialGenerator(mean)
    override protected def nextValue(event: Int): Long = generator.nextValue().longValue
  }

  /**
   * Generate id events for a hotspot distribution, where x% ('rate') of operations access y% ('hot') of the id space.
   */
  final class Hotspot(min: Long, max: Long, hot: Double, rate: Double, events: Int) extends SyntheticGenerator(events) {
    private val generator = new HotspotIntegerGenerator(min, max, hot, rate)
    override protected def nextValue(event: Int): Long = generator.nextValue()
  }

  /**
   * Generate id events where some ids in the id space are more popular than others, based on a zipfian distribution.
   * If scrambled, the popular ids are scattered over the id space.
   */
  final class Zipfian(min: Long, max: Long, constant: Double, scrambled: Boolean, events: Int)
      extends SyntheticGenerator(events) {
    private val generator =
      if (scrambled) new ScrambledZipfianGenerator(min, max, constant) else new ZipfianGenerator(min, max, constant)
    override protected def nextValue(event: Int): Long = generator.nextValue().longValue
  }

  /**
   * Generate id events where some ids are more popular than others, based on a zipfian distribution, and the popular
   * ids are shifted periodically (divided evenly across the id space and the total number of events).
   * If scrambled, the popular ids are also scattered over the id space.
   */
  final class ShiftingZipfian(min: Long, max: Long, constant: Double, shifts: Int, scrambled: Boolean, events: Int)
      extends SyntheticGenerator(events) {
    private val numberOfIds = max - min + 1
    private val shiftBy = numberOfIds / shifts
    private val shiftEvery = events / shifts

    private val generator =
      if (scrambled) new ScrambledZipfianGenerator(0, numberOfIds - 1, constant)
      else new ZipfianGenerator(numberOfIds, constant)

    override protected def nextValue(event: Int): Long = {
      val shift = event / shiftEvery * shiftBy
      min + ((generator.nextValue().longValue + shift) % numberOfIds)
    }
  }
}

abstract class TraceFileReader(path: String) extends AccessPattern {
  protected def lines: Source[String, NotUsed] =
    FileIO
      .fromPath(Paths.get(path))
      .via(Framing.delimiter(ByteString("\n"), maximumFrameLength = 256, allowTruncation = true))
      .map(_.utf8String)
      .mapMaterializedValue(_ => NotUsed)
}

object TraceFileReader {

  /**
   * Simple trace file format: entity id per line.
   */
  final class Simple(path: String) extends TraceFileReader(path: String) {
    override def entityIds: Source[EntityId, NotUsed] = lines
  }

  /**
   * Read traces provided with the "ARC" paper.
   * Nimrod Megiddo and Dharmendra S. Modha, "ARC: A Self-Tuning, Low Overhead Replacement Cache".
   */
  final class Arc(path: String) extends TraceFileReader(path: String) {
    override def entityIds: Source[EntityId, NotUsed] = lines.mapConcat { line =>
      val parts = line.split(" ")
      val startId = parts(0).toLong
      val numberOfIds = parts(1).toInt
      (startId until (startId + numberOfIds)).map(_.toString)
    }
  }
}
