/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

import java.util.concurrent.TimeUnit

import akka.stream.impl.JsonObjectParser
import akka.util.ByteString
import org.openjdk.jmh.annotations._

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.SECONDS)
@BenchmarkMode(Array(Mode.Throughput))
class JsonFramingBenchmark {

  val json =
    ByteString(
      """{"fname":"Frank","name":"Smith","age":42,"id":1337,"boardMember":false}"""
    )

  val json5 =
    ByteString(
      """|{"fname":"Frank","name":"Smith","age":42,"id":1337,"boardMember":false},
         |{"fname":"Bob","name":"Smith","age":42,"id":1337,"boardMember":false},
         |{"fname":"Bob","name":"Smith","age":42,"id":1337,"boardMember":false},
         |{"fname":"Bob","name":"Smith","age":42,"id":1337,"boardMember":false},
         |{"fname":"Hank","name":"Smith","age":42,"id":1337,"boardMember":false}""".stripMargin
    )

  val json5by5 = for (i ← 0.until(json5.length, 5)) yield json5.slice(i, i + 5).compact

  val jsonLong =
    ByteString(
      s"""{"fname":"Frank","name":"Smith","age":42,"id":1337,"boardMember":false,"description":"${"a" * 1000000}"}"""
    )

  val bracket = new JsonObjectParser

  @Benchmark
  def counting_1: ByteString = {
    bracket.offer(json)
    bracket.poll().get
  }

  @Benchmark
  @OperationsPerInvocation(5)
  def counting_offer_5: ByteString = {
    bracket.offer(json5)
    bracket.poll().get
    bracket.poll().get
    bracket.poll().get
    bracket.poll().get
    bracket.poll().get
  }

  @Benchmark
  @OperationsPerInvocation(25)
  def counting_offer_5x5: ByteString = {
    /* this test validates that we don't create too much internal buffer misalignment-caused loss of performance.
     */

    for (i ← 0 until 5) {
      bracket.offer(json5)
    }

    for (i ← 0 until 24) {
      bracket.poll().get
    }
    bracket.poll().get // 25
  }

  @Benchmark
  @OperationsPerInvocation(5)
  def counting_offer_5by5bytes: ByteString = {
    /* this test validates how we JsonObjectParser when the incoming data does not come by object boundaries */
    json5by5.foreach(bracket.offer)

    for (i ← 0 until 4) {
      bracket.poll().get
    }
    bracket.poll().get // 5
  }

  @Benchmark
  def counting_long_document: ByteString = {
    bracket.offer(jsonLong)
    bracket.poll().get
  }

}
