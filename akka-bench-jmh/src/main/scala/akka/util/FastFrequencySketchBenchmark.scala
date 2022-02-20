/*
 * Copyright (C) 2021-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.util

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup

import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 3, time = 20, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 10, timeUnit = TimeUnit.SECONDS)
class FastFrequencySketchBenchmark {
  private[this] val Capacity = 10000
  private[this] val GeneratedSize = 1 << 16
  private final val IndexMask = 0xFFFF

  private[this] var sketch: FastFrequencySketch[String] = _
  private[this] var generated: Array[String] = _
  private[this] var index: Int = 0

  @Setup
  def setup(): Unit = {
    sketch = FastFrequencySketch[String](Capacity)
    generated = new Array[String](GeneratedSize)
    val generator = ZipfianGenerator(GeneratedSize)
    for (i <- 0 until GeneratedSize) {
      generated(i) = generator.next().intValue.toString
      sketch.increment(i.toString)
    }
  }

  @Benchmark
  def increment(): Unit = {
    sketch.increment(generated(index & IndexMask))
    index += 1
  }

  @Benchmark
  def frequency: Int = {
    val count = sketch.frequency(generated(index & IndexMask))
    index += 1
    count
  }
}
