/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.compress

import java.util.Random

import akka.actor.{ ActorSystem, Address }
import akka.event.NoLogging
import akka.remote.artery.compress.{ OutboundCompressionTable, TopHeavyHitters }
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@Fork(2)
class OutboundCompressionTableBenchmark {

  @Param(Array("512", "8192"))
  var registered: Int = 0

  implicit val system = ActorSystem("TestSystem")

  var outgoingCompression: OutboundCompressionTable[String] = _

  val rand = new Random(1001021)

  var preallocatedNums: Array[Long] = _
  var preallocatedStrings: Array[String] = _

  var i = 0

  @Setup
  def init(): Unit = {
    preallocatedNums = Array.ofDim(registered)
    preallocatedStrings = Array.ofDim(8192)

    outgoingCompression = new OutboundCompressionTable(system, Address("akka", "remote-system"))

    var i = 0
    while (i < registered) {
      outgoingCompression.register(i.toString, i)
      preallocatedNums(i) = rand.nextLong()
      preallocatedStrings(i) = i.toString
      i += 1
    }
  }

  //  @Benchmark
  //  @BenchmarkMode(Array(Mode.SingleShotTime))
  //  def registerThenCompress(): Int = {
  //    outgoingCompression.register("new", i)
  //    outgoingCompression.compress("new")
  //  }

  @Benchmark
  def compressKnown(): Int =
    outgoingCompression.compress("1")

}
