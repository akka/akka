/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.serialization.jackson

import java.time.Instant
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

import scala.concurrent.Await
import scala.concurrent.duration._

import akka.actor._
import akka.serialization.Serialization
import akka.serialization.SerializationExtension
import akka.serialization.SerializerWithStringManifest
import com.typesafe.config.ConfigFactory
import org.openjdk.jmh.annotations._

object JacksonSerializationBench {
  trait TestMessage

  final case class Small(name: String, num: Int) extends TestMessage

  final case class Medium(
      field1: String,
      field2: String,
      field3: String,
      num1: Int,
      num2: Int,
      num3: Int,
      flag1: Boolean,
      flag2: Boolean,
      duration: FiniteDuration,
      date: LocalDateTime,
      instant: Instant,
      nested1: Small,
      nested2: Small,
      nested3: Small)
      extends TestMessage

  final case class Large(
      nested1: Medium,
      nested2: Medium,
      nested3: Medium,
      vector: Vector[Medium],
      map: Map[String, Medium])
      extends TestMessage

  final class TimeMessage(val duration: FiniteDuration, val date: LocalDateTime, val instant: Instant)
      extends TestMessage

  // FIXME try with plain java classes (not case class)
}

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Array(Mode.Throughput))
@Fork(2)
@Warmup(iterations = 4)
@Measurement(iterations = 5)
class JacksonSerializationBench {
  import JacksonSerializationBench._

  val smallMsg1 = Small("abc", 17)
  val smallMsg2 = Small("def", 18)
  val smallMsg3 = Small("ghi", 19)
  val mediumMsg1 = Medium(
    "abc",
    "def",
    "ghi",
    1,
    2,
    3,
    false,
    true,
    5.seconds,
    LocalDateTime.of(2019, 4, 29, 23, 15, 3, 12345),
    Instant.now(),
    smallMsg1,
    smallMsg2,
    smallMsg3)
  val mediumMsg2 = Medium(
    "ABC",
    "DEF",
    "GHI",
    10,
    20,
    30,
    true,
    false,
    5.millis,
    LocalDateTime.of(2019, 4, 29, 23, 15, 4, 12345),
    Instant.now(),
    smallMsg1,
    smallMsg2,
    smallMsg3)
  val mediumMsg3 = Medium(
    "abcABC",
    "defDEF",
    "ghiGHI",
    100,
    200,
    300,
    true,
    true,
    200.millis,
    LocalDateTime.of(2019, 4, 29, 23, 15, 5, 12345),
    Instant.now(),
    smallMsg1,
    smallMsg2,
    smallMsg3)
  val largeMsg = Large(
    mediumMsg1,
    mediumMsg2,
    mediumMsg3,
    Vector(mediumMsg1, mediumMsg2, mediumMsg3),
    Map("a" -> mediumMsg1, "b" -> mediumMsg2, "c" -> mediumMsg3))

  val timeMsg = new TimeMessage(5.seconds, LocalDateTime.of(2019, 4, 29, 23, 15, 3, 12345), Instant.now())

  var system: ActorSystem = _
  var serialization: Serialization = _

  @Param(Array("jackson-json", "jackson-cbor")) // "java"
  private var serializerName: String = _

  @Setup(Level.Trial)
  def setupTrial(): Unit = {
    val config = ConfigFactory.parseString(s"""
        akka {
          loglevel = WARNING
          actor {
            serialization-bindings {
              "akka.serialization.jackson.JacksonSerializationBench$$TestMessage" = $serializerName
            }
          }
          serialization.jackson {
            compress-larger-than = 100000 b
            
            serialization-features {
              #WRITE_DATES_AS_TIMESTAMPS = off
            }
          }
        }
      """)

    system = ActorSystem("JacksonSerializationBench", config)
    serialization = SerializationExtension(system)
  }

  @TearDown(Level.Trial)
  def tearDownTrial(): Unit = {
    Await.result(system.terminate(), 5.seconds)
  }

  private def serializeDeserialize[T <: AnyRef](msg: T): T = {
    serialization.findSerializerFor(msg) match {
      case serializer: SerializerWithStringManifest ⇒
        val blob = serializer.toBinary(msg)
        serializer.fromBinary(blob, serializer.manifest(msg)).asInstanceOf[T]
      case serializer ⇒
        val blob = serializer.toBinary(msg)
        if (serializer.includeManifest)
          serializer.fromBinary(blob, Some(msg.getClass)).asInstanceOf[T]
        else
          serializer.fromBinary(blob, None).asInstanceOf[T]
    }

  }

  @Benchmark
  def small(): Small = {
    serializeDeserialize(smallMsg1)
  }

  @Benchmark
  def medium(): Medium = {
    serializeDeserialize(mediumMsg1)
  }

  @Benchmark
  def large(): Large = {
    serializeDeserialize(largeMsg)
  }

  @Benchmark
  def timeMessage(): TimeMessage = {
    serializeDeserialize(timeMsg)
  }

}
