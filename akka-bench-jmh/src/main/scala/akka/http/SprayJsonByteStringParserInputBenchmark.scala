/*
 * Copyright (C) 2016-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http

import java.util.concurrent.TimeUnit

import akka.http.scaladsl.marshallers.sprayjson.{ SprayJsonSupport, SprayJsonByteStringParserInput }
import akka.util.ByteString
import akka.util.ByteString.{ ByteString1C, ByteStrings }
import org.openjdk.jmh.annotations.{ Benchmark, Measurement, Scope, State }
import spray.json._

@State(Scope.Benchmark)
@Measurement(timeUnit = TimeUnit.MILLISECONDS)
class SprayJsonByteStringParserInputBenchmark {

  case class Person(firstName: String, middleName: String, lastName: String, age: Int)

  object PersonJsonSupport extends DefaultJsonProtocol with SprayJsonSupport {
    implicit val personFormat = jsonFormat4(Person)
  }

  val longFirstName = "Stephen" * 256
  val longMiddleName = "William" * 256
  val longLastName = "Hawking" * 256
  val jsonString =
    s"""
      |{
      |  "firstName": "$longFirstName",
      |  "middleName": "$longMiddleName",
      |  "lastName": "$longLastName",
      |  "age": 27
      |}
    """.stripMargin

  def mkByteStringVector(bsSize: Int): Vector[ByteString] = {
    val arraySize =
      if (jsonString.length % bsSize == 0) {
        jsonString.length / bsSize
      } else {
        (jsonString.length / bsSize) + 1
      }

    jsonString.toVector
      .grouped(arraySize)
      .map(_.mkString)
      .toVector
      .map(ByteString(_))
  }

  val small = 10
  val large = 100
  val xlarge = jsonString.length

  val smallVector = mkByteStringVector(small)
  val largeVector = mkByteStringVector(large)
  val xlargeVector = mkByteStringVector(xlarge)

  val bss_small = ByteStrings(smallVector.map(_.asInstanceOf[ByteString1C].toByteString1))
  val bss_large = ByteStrings(largeVector.map(_.asInstanceOf[ByteString1C].toByteString1))
  val bss_xlarge = ByteStrings(xlargeVector.map(_.asInstanceOf[ByteString1C].toByteString1))

  /*
    [info] Benchmark                                                           Mode  Cnt      Score     Error  Units
    [info] SprayJsonByteStringParserInputBenchmark.bss_small_parser           thrpt   20   7424.216 ± 115.665  ops/s
    [info] SprayJsonByteStringParserInputBenchmark.bss_small_parser_after     thrpt   20  14379.907 ± 220.254  ops/s
    [info] SprayJsonByteStringParserInputBenchmark.bss_small_parser_compact   thrpt   20  27472.608 ± 193.680  ops/s

    [info] SprayJsonByteStringParserInputBenchmark.bss_large_parser           thrpt   20    661.998 ±   8.193  ops/s
    [info] SprayJsonByteStringParserInputBenchmark.bss_large_parser_after     thrpt   20   9675.699 ± 100.942  ops/s
    [info] SprayJsonByteStringParserInputBenchmark.bss_large_parser_compact   thrpt   20  24174.920 ± 314.113  ops/s

    [info] SprayJsonByteStringParserInputBenchmark.bss_xlarge_parser          thrpt   20      9.442 ±   0.108  ops/s
    [info] SprayJsonByteStringParserInputBenchmark.bss_xlarge_parser_after    thrpt   20   5634.490 ±  71.075  ops/s
    [info] SprayJsonByteStringParserInputBenchmark.bss_xlarge_parser_compact  thrpt   20   7657.936 ± 125.968  ops/s
  */
  import PersonJsonSupport._

  /* BEFORE */
  @Benchmark
  def bss_small_parser(): Unit = {
    val parserInput = new SprayJsonByteStringParserInput(bss_small)
    val jsValue = JsonParser(parserInput)
    jsonReader[Person].read(jsValue)
  }

  @Benchmark
  def bss_large_parser(): Unit = {
    val parserInput = new SprayJsonByteStringParserInput(bss_large)
    val jsValue = JsonParser(parserInput)
    jsonReader[Person].read(jsValue)
  }

  @Benchmark
  def bss_xlarge_parser(): Unit = {
    val parserInput = new SprayJsonByteStringParserInput(bss_xlarge)
    val jsValue = JsonParser(parserInput)
    jsonReader[Person].read(jsValue)
  }

  /* COMPACT */
  @Benchmark
  def bss_small_parser_compact(): Unit = {
    val parserInput = new SprayJsonByteStringParserInput(bss_small.compact)
    val jsValue = JsonParser(parserInput)
    jsonReader[Person].read(jsValue)
  }

  @Benchmark
  def bss_large_parser_compact(): Unit = {
    val parserInput = new SprayJsonByteStringParserInput(bss_large.compact)
    val jsValue = JsonParser(parserInput)
    jsonReader[Person].read(jsValue)
  }

  @Benchmark
  def bss_xlarge_parser_compact(): Unit = {
    val parserInput = new SprayJsonByteStringParserInput(bss_xlarge.compact)
    val jsValue = JsonParser(parserInput)
    jsonReader[Person].read(jsValue)
  }

  /* AFTER */
  @Benchmark
  def bss_small_parser_after(): Unit = {
    val parserInput = new SprayJsonByteStringParserInput(
      bss_small.asInstanceOf[ByteStrings].fastScannable
    )
    val jsValue = JsonParser(parserInput)
    jsonReader[Person].read(jsValue)
  }

  @Benchmark
  def bss_large_parser_after(): Unit = {
    val parserInput = new SprayJsonByteStringParserInput(
      bss_large.asInstanceOf[ByteStrings].fastScannable
    )
    val jsValue = JsonParser(parserInput)
    jsonReader[Person].read(jsValue)
  }

  @Benchmark
  def bss_xlarge_parser_after(): Unit = {
    val parserInput = new SprayJsonByteStringParserInput(
      bss_xlarge.asInstanceOf[ByteStrings].fastScannable
    )
    val jsValue = JsonParser(parserInput)
    jsonReader[Person].read(jsValue)
  }

}
