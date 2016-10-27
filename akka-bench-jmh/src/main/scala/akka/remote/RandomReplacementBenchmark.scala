/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote

import java.security.SecureRandom
import java.util.concurrent.TimeUnit

import akka.remote.security.provider.{ AESCounterBuiltinCTRRNG, AESCounterBuiltinRNG }
import org.openjdk.jmh.annotations._
import org.uncommons.maths.random.AESCounterRNG

/*
akka-bench-jmh > jmh:run -wi 15 -i 15 -f 1 -t 1 .*RandomReplacementBenchmark.*

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

[info] Benchmark                          Mode  Cnt    Score    Error  Units
[info] RandomReplacementBenchmark.runCtr    ss   15  299.485 ± 23.466  ms/op
[info] RandomReplacementBenchmark.runNew    ss   15  238.772 ± 12.127  ms/op
[info] RandomReplacementBenchmark.runOld    ss   15  215.453 ± 24.637  ms/op

[info] RandomReplacementBenchmark.runCtr    ss   15  283.690 ± 17.258  ms/op
[info] RandomReplacementBenchmark.runNew    ss   15  251.412 ± 25.066  ms/op
[info] RandomReplacementBenchmark.runOld    ss   15  231.133 ± 29.139  ms/op

[info] RandomReplacementBenchmark.runCtr    ss   15  314.895 ± 45.711  ms/op
[info] RandomReplacementBenchmark.runNew    ss   15  250.495 ± 15.762  ms/op
[info] RandomReplacementBenchmark.runOld    ss   15  215.201 ± 23.651  ms/op

[info] Benchmark                             Mode  Cnt     Score     Error  Units
[info] RandomReplacementBenchmark.runCtr       ss   15   297.946 ±  39.956  ms/op
[info] RandomReplacementBenchmark.runNew       ss   15   264.695 ±  35.462  ms/op
[info] RandomReplacementBenchmark.runOld       ss   15   275.792 ±  63.000  ms/op
[info] RandomReplacementBenchmark.runSecRnd    ss   15  3143.013 ± 378.316  ms/op

[info] Benchmark                             Mode  Cnt     Score     Error  Units
[info] RandomReplacementBenchmark.runCtr       ss   15   335.211 ±  77.456  ms/op
[info] RandomReplacementBenchmark.runNew       ss   15   261.172 ±  48.231  ms/op
[info] RandomReplacementBenchmark.runOld       ss   15   246.999 ±  36.847  ms/op
[info] RandomReplacementBenchmark.runSecRnd    ss   15  3172.568 ± 449.505  ms/op


 */

object SystemUnderTest {
  private final val SOURCE = new SecureRandom
  private final val seed = SOURCE.generateSeed(32)
  final val rngCtr = new AESCounterBuiltinCTRRNG(seed)
  final val rngNew = new AESCounterBuiltinRNG(seed)
  final val rngOld = new AESCounterRNG(seed)
  final val rngSecRnd = new SecureRandom
}

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.SingleShotTime))
@Fork(5)
@Threads(1)
@Warmup(iterations = 10)
@Measurement(iterations = 10)
class RandomReplacementBenchmark {
  var inputCtr: Array[Byte] = null
  var inputNew: Array[Byte] = null
  var inputOld: Array[Byte] = null
  var inputSecRnd: Array[Byte] = null

  @Setup(Level.Trial)
  def setup(): Unit = {
  }

  @TearDown(Level.Trial)
  def shutdown(): Unit = {
  }

  @Setup(Level.Iteration)
  def setupIteration(): Unit = {
    inputCtr = Array.fill[Byte](10000000)(1)
    inputNew = Array.fill[Byte](10000000)(1)
    inputOld = Array.fill[Byte](10000000)(1)
    inputSecRnd = Array.fill[Byte](10000000)(1)
  }

  @TearDown(Level.Iteration)
  def shutdownIteration(): Unit = {
  }

  @Benchmark
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  def runCtr(): Unit = {
    SystemUnderTest.rngCtr.nextBytes(inputCtr)
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

  @Benchmark
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  def runSecRnd(): Unit = {
    SystemUnderTest.rngSecRnd.nextBytes(inputSecRnd)
  }
}
