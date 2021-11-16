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
  protected def createGenerator(): site.ycsb.generator.NumberGenerator

  override def entityIds: Source[EntityId, NotUsed] = Source.unfold(createGenerator() -> 0) {
    case (_, count) if count >= events => None
    case (generator, count)            => Option(generator.nextValue().longValue.toString).map(generator -> (count + 1) -> _)
  }
}

object SyntheticGenerator {
  import site.ycsb.generator._

  /**
   * Generate a sequence of unique id events.
   */
  final class Sequence(start: Long, events: Int) extends SyntheticGenerator(events) {
    override protected def createGenerator(): NumberGenerator = new CounterGenerator(start)
  }

  /**
   * Generate id events randomly using a uniform distribution, from the inclusive range min to max.
   */
  final class Uniform(min: Long, max: Long, events: Int) extends SyntheticGenerator(events) {
    override protected def createGenerator(): NumberGenerator = new UniformLongGenerator(min, max)
  }

  /**
   * Generate id events based on an exponential distribution given the mean (expected value) of the distribution.
   */
  final class Exponential(mean: Double, events: Int) extends SyntheticGenerator(events) {
    override protected def createGenerator(): NumberGenerator = new ExponentialGenerator(mean)
  }

  /**
   * Generate id events for a hotspot distribution, where x% ('rate') of operations access y% ('hot') of the id space.
   */
  final class Hotspot(min: Long, max: Long, hot: Double, rate: Double, events: Int) extends SyntheticGenerator(events) {
    override protected def createGenerator(): NumberGenerator = new HotspotIntegerGenerator(min, max, hot, rate)
  }

  /**
   * Generate id events where some ids in the id space are more popular than others, based on a zipfian distribution.
   */
  final class Zipfian(min: Long, max: Long, constant: Double, events: Int) extends SyntheticGenerator(events) {
    override protected def createGenerator(): NumberGenerator = new ZipfianGenerator(min, max, constant)
  }

  /**
   * Generate id events where some ids are more popular than others, based on a zipfian distribution, and the popular
   * ids are scattered over the id space.
   */
  final class ScrambledZipfian(min: Long, max: Long, constant: Double, events: Int) extends SyntheticGenerator(events) {
    override protected def createGenerator(): NumberGenerator = new ScrambledZipfianGenerator(min, max, constant)
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
