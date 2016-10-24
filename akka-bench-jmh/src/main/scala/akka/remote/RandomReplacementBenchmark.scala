/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote

import java.security.SecureRandom
import java.util.concurrent.TimeUnit

import akka.remote.security.provider.AESCounterBuiltinRNG
import org.openjdk.jmh.annotations._
import org.uncommons.maths.random.AESCounterRNG

/*
akka-bench-jmh > jmh:run -wi 15 -i 15 -f 1 .*RandomReplacementBenchmark.*

[...]

[info] Benchmark                          Mode  Cnt    Score    Error  Units
[info] RandomReplacementBenchmark.runNew    ss   15  254.022 ± 31.966  ms/op
[info] RandomReplacementBenchmark.runOld    ss   15  261.415 ± 36.318  ms/op

[info] RandomReplacementBenchmark.runNew    ss   15  372.153 ± 23.552  ms/op
[info] RandomReplacementBenchmark.runOld    ss   15  306.477 ± 32.487  ms/op

[info] RandomReplacementBenchmark.runNew    ss   15  249.846 ± 28.165  ms/op
[info] RandomReplacementBenchmark.runOld    ss   15  210.568 ± 14.917  ms/op

[info] RandomReplacementBenchmark.runNew    ss   15  373.674 ±  85.931  ms/op
[info] RandomReplacementBenchmark.runOld    ss   15  384.629 ± 101.545  ms/op

[info] RandomReplacementBenchmark.runNew    ss   15  249.659 ± 20.295  ms/op
[info] RandomReplacementBenchmark.runOld    ss   15  262.815 ± 49.112  ms/op

 */

object SystemUnderTest {
  private final val SOURCE = new SecureRandom
  private final val seed = SOURCE.generateSeed(32)
  final val rngNew = new AESCounterBuiltinRNG(seed)
  final val rngOld = new AESCounterRNG(seed)
}

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.SingleShotTime))
@Fork(5)
@Threads(1)
@Warmup(iterations = 10)
@Measurement(iterations = 10)
class RandomReplacementBenchmark {
  var inputNew: Array[Byte] = null
  var inputOld: Array[Byte] = null

  @Setup(Level.Trial)
  def setup(): Unit = {
  }

  @TearDown(Level.Trial)
  def shutdown(): Unit = {
  }

  @Setup(Level.Iteration)
  def setupIteration(): Unit = {
    inputNew = Array.fill[Byte](10000000)(1)
    inputOld = Array.fill[Byte](10000000)(1)
  }

  @TearDown(Level.Iteration)
  def shutdownIteration(): Unit = {
  }

  @Benchmark
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  def runNew(): Unit = {
    SystemUnderTest.rngNew.nextBytes(inputNew)
  }

  @Benchmark
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  def runOld(): Unit = {
    SystemUnderTest.rngOld.nextBytes(inputOld)
  }
}
