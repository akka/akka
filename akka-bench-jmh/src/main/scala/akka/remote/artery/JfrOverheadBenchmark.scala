/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OperationsPerInvocation
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Array(Mode.Throughput))
@Fork(1)
@Warmup(iterations = 2)
@Measurement(iterations = 2)
class JfrOverheadBenchmark {

  @Benchmark
  @OperationsPerInvocation(100000)
  def withJfr(blackhole: Blackhole): Unit = {
    for {
      _ <- 0 to 100000
    } {
      blackhole.consume(countWithJfr())
    }
  }

  @Benchmark
  @OperationsPerInvocation(100000)
  def withoutJfr(blackhole: Blackhole): Unit = {
    for {
      _ <- 0 to 100000
    } {
      blackhole.consume(countWithoutJfr())
    }
  }

  def countWithoutJfr(): Int = {
    var n = 0
    n += 1
    n += 1
    n += 1
    n += 1
    n += 1
    n += 1
    n += 1
    n += 1
    n += 1
    n += 1
    n
  }

  def countWithJfr(): Int = {
    var n = 0
    RemotingFlightRecorder.transportStarted()
    n += 1
    RemotingFlightRecorder.tcpInboundReceived(128)
    n += 1
    RemotingFlightRecorder.tcpOutboundSent(40)
    n += 1
    RemotingFlightRecorder.tcpInboundReceived(100)
    n += 1
    RemotingFlightRecorder.tcpOutboundSent(100)
    n += 1
    RemotingFlightRecorder.tcpInboundReceived(52)
    n += 1
    RemotingFlightRecorder.tcpOutboundSent(50)
    n += 1
    RemotingFlightRecorder.tcpInboundReceived(52)
    n += 1
    RemotingFlightRecorder.tcpInboundReceived(81)
    n += 1
    RemotingFlightRecorder.transportStopped()
    n += 1
    n
  }

}
