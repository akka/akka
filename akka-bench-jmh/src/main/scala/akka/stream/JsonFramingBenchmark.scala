/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
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

  /*
    Benchmark                                 Mode  Cnt      Score      Error  Units
    // old
    JsonFramingBenchmark.collecting_1        thrpt   20     81.476 ±   14.793  ops/s
    JsonFramingBenchmark.collecting_offer_5  thrpt   20     20.187 ±    2.291  ops/s

    // new
    JsonFramingBenchmark.counting_1          thrpt   20  10766.738 ± 1278.300  ops/s
    JsonFramingBenchmark.counting_offer_5    thrpt   20  28798.255 ± 2670.163  ops/s
   */

  val json =
    ByteString(
      """|{"fname":"Frank","name":"Smith","age":42,"id":1337,"boardMember":false},
        |{"fname":"Bob","name":"Smith","age":42,"id":1337,"boardMember":false},
        |{"fname":"Bob","name":"Smith","age":42,"id":1337,"boardMember":false},
        |{"fname":"Bob","name":"Smith","age":42,"id":1337,"boardMember":false},
        |{"fname":"Bob","name":"Smith","age":42,"id":1337,"boardMember":false},
        |{"fname":"Bob","name":"Smith","age":42,"id":1337,"boardMember":false},
        |{"fname":"Hank","name":"Smith","age":42,"id":1337,"boardMember":false}""".stripMargin
    )

  val bracket = new JsonObjectParser

  @Setup(Level.Invocation)
  def init(): Unit = {
    bracket.offer(json)
  }

  @Benchmark
  def counting_1: ByteString =
    bracket.poll().get

  @Benchmark
  @OperationsPerInvocation(5)
  def counting_offer_5: ByteString = {
    bracket.offer(json)
    bracket.poll().get
    bracket.poll().get
    bracket.poll().get
    bracket.poll().get
    bracket.poll().get
    bracket.poll().get
  }

}
