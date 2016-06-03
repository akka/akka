/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import akka.util.Timeout
import org.openjdk.jmh.annotations._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{ Await, Promise }

/*
[info] Benchmark                                    (ratio) (to)   Mode   Samples        Score  Score error    Units
[info] a.a.ScheduleBenchmark.multipleScheduleOnce       0.1    4  thrpt        40   397174.273    18707.983    ops/s
[info] a.a.ScheduleBenchmark.multipleScheduleOnce       0.1   16  thrpt        40    89385.115     3198.783    ops/s
[info] a.a.ScheduleBenchmark.multipleScheduleOnce       0.1   64  thrpt        40    26152.329     2291.895    ops/s
[info] a.a.ScheduleBenchmark.multipleScheduleOnce      0.35    4  thrpt        40   383100.418    15052.818    ops/s
[info] a.a.ScheduleBenchmark.multipleScheduleOnce      0.35   16  thrpt        40    83574.143     6612.393    ops/s
[info] a.a.ScheduleBenchmark.multipleScheduleOnce      0.35   64  thrpt        40    20509.715     2814.356    ops/s
[info] a.a.ScheduleBenchmark.multipleScheduleOnce       0.9    4  thrpt        40   367227.500    16169.665    ops/s
[info] a.a.ScheduleBenchmark.multipleScheduleOnce       0.9   16  thrpt        40    72611.445     4086.267    ops/s
[info] a.a.ScheduleBenchmark.multipleScheduleOnce       0.9   64  thrpt        40     7332.554     1087.250    ops/s
[info] a.a.ScheduleBenchmark.oneSchedule                0.1    4  thrpt        40  1040918.731    21830.348    ops/s
[info] a.a.ScheduleBenchmark.oneSchedule                0.1   16  thrpt        40  1036284.894    26962.984    ops/s
[info] a.a.ScheduleBenchmark.oneSchedule                0.1   64  thrpt        40   944350.638    32055.335    ops/s
[info] a.a.ScheduleBenchmark.oneSchedule               0.35    4  thrpt        40  1045371.779    34943.155    ops/s
[info] a.a.ScheduleBenchmark.oneSchedule               0.35   16  thrpt        40   954663.161    18032.730    ops/s
[info] a.a.ScheduleBenchmark.oneSchedule               0.35   64  thrpt        40   739593.387    21132.531    ops/s
[info] a.a.ScheduleBenchmark.oneSchedule                0.9    4  thrpt        40  1046392.800    29542.291    ops/s
[info] a.a.ScheduleBenchmark.oneSchedule                0.9   16  thrpt        40   820986.574    22058.708    ops/s
[info] a.a.ScheduleBenchmark.oneSchedule                0.9   64  thrpt        40   210115.907    14176.402    ops/s
 */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@Fork(2)
@Warmup(iterations = 10, time = 1700, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 20, time = 1700, timeUnit = TimeUnit.MILLISECONDS)
class ScheduleBenchmark {
  implicit val system: ActorSystem = ActorSystem()
  val scheduler: Scheduler = system.scheduler
  val interval: FiniteDuration = 25.millis
  val within: FiniteDuration = 2.seconds
  implicit val timeout: Timeout = Timeout(within)

  @Param(Array("4", "16", "64"))
  var to = 0

  @Param(Array("0.1", "0.35", "0.9"))
  var ratio = 0d

  var winner: Int = _
  var promise: Promise[Any] = _

  @Setup(Level.Iteration)
  def setup(): Unit = {
    winner = (to * ratio + 1).toInt
    promise = Promise[Any]()
  }

  @TearDown
  def shutdown(): Unit = {
    system.terminate()
    Await.ready(system.whenTerminated, 15.seconds)
  }

  def op(idx: Int) = if (idx == winner) promise.trySuccess(idx) else idx

  @Benchmark
  def oneSchedule(): Unit = {
    val aIdx = new AtomicInteger(1)
    val tryWithNext = scheduler.schedule(0.millis, interval) {
      val idx = aIdx.getAndIncrement
      if (idx <= to) op(idx)
    }
    promise.future.onComplete {
      case _ ⇒
        tryWithNext.cancel()
    }
    Await.result(promise.future, within)
  }

  @Benchmark
  def multipleScheduleOnce(): Unit = {
    val tryWithNext = (1 to to).foldLeft(0.millis -> List[Cancellable]()) {
      case ((interv, c), idx) ⇒
        (interv + interval, scheduler.scheduleOnce(interv) {
          op(idx)
        } :: c)
    }._2
    promise.future.onComplete {
      case _ ⇒
        tryWithNext.foreach(_.cancel())
    }
    Await.result(promise.future, within)
  }
}
