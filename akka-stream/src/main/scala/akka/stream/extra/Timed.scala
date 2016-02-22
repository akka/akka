/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.extra

import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration._
import scala.language.existentials
import akka.stream.scaladsl.{ Source, Flow }
import akka.stream.stage._

/**
 * Provides operations needed to implement the `timed` DSL
 */
private[akka] trait TimedOps {

  import Timed._

  /**
   * INTERNAL API
   *
   * Measures time from receiving the first element and completion events - one for each subscriber of this `Flow`.
   */
  def timed[I, O, Mat, Mat2](source: Source[I, Mat], measuredOps: Source[I, Mat] ⇒ Source[O, Mat2], onComplete: FiniteDuration ⇒ Unit): Source[O, Mat2] = {
    val ctx = new TimedFlowContext

    val startTimed = Flow[I].transform(() ⇒ new StartTimed(ctx)).named("startTimed")
    val stopTimed = Flow[O].transform(() ⇒ new StopTimed(ctx, onComplete)).named("stopTimed")

    measuredOps(source.via(startTimed)).via(stopTimed)
  }

  /**
   * INTERNAL API
   *
   * Measures time from receiving the first element and completion events - one for each subscriber of this `Flow`.
   */
  def timed[I, O, Out, Mat, Mat2](flow: Flow[I, O, Mat], measuredOps: Flow[I, O, Mat] ⇒ Flow[I, Out, Mat2], onComplete: FiniteDuration ⇒ Unit): Flow[I, Out, Mat2] = {
    // todo is there any other way to provide this for Flow, without duplicating impl?
    // they do share a super-type (FlowOps), but all operations of FlowOps return path dependant type
    val ctx = new TimedFlowContext

    val startTimed = Flow[O].transform(() ⇒ new StartTimed(ctx)).named("startTimed")
    val stopTimed = Flow[Out].transform(() ⇒ new StopTimed(ctx, onComplete)).named("stopTimed")

    measuredOps(flow.via(startTimed)).via(stopTimed)
  }

}

/**
 * INTERNAL API
 *
 * Provides operations needed to implement the `timedIntervalBetween` DSL
 */
private[akka] trait TimedIntervalBetweenOps {

  import Timed._

  /**
   * Measures rolling interval between immediately subsequent `matching(o: O)` elements.
   */
  def timedIntervalBetween[O, Mat](source: Source[O, Mat], matching: O ⇒ Boolean, onInterval: FiniteDuration ⇒ Unit): Source[O, Mat] = {
    val timedInterval = Flow[O].transform(() ⇒ new TimedInterval[O](matching, onInterval)).named("timedInterval")
    source.via(timedInterval)
  }

  /**
   * Measures rolling interval between immediately subsequent `matching(o: O)` elements.
   */
  def timedIntervalBetween[I, O, Mat](flow: Flow[I, O, Mat], matching: O ⇒ Boolean, onInterval: FiniteDuration ⇒ Unit): Flow[I, O, Mat] = {
    val timedInterval = Flow[O].transform(() ⇒ new TimedInterval[O](matching, onInterval)).named("timedInterval")
    flow.via(timedInterval)
  }
}

object Timed extends TimedOps with TimedIntervalBetweenOps {

  // todo needs java DSL

  final class TimedFlowContext {
    import scala.concurrent.duration._

    private val _start = new AtomicLong
    private val _stop = new AtomicLong

    def start(): Unit = {
      _start.compareAndSet(0, System.nanoTime())
    }

    def stop(): FiniteDuration = {
      _stop.compareAndSet(0, System.nanoTime())
      compareStartAndStop()
    }

    private def compareStartAndStop(): FiniteDuration = {
      val stp = _stop.get
      if (stp <= 0) Duration.Zero
      else (stp - _start.get).nanos
    }
  }

  final class StartTimed[T](timedContext: TimedFlowContext) extends PushStage[T, T] {
    private var started = false

    override def onPush(elem: T, ctx: Context[T]): SyncDirective = {
      if (!started) {
        timedContext.start()
        started = true
      }
      ctx.push(elem)
    }
  }

  final class StopTimed[T](timedContext: TimedFlowContext, _onComplete: FiniteDuration ⇒ Unit) extends PushStage[T, T] {

    override def onPush(elem: T, ctx: Context[T]): SyncDirective = ctx.push(elem)

    override def onUpstreamFailure(cause: Throwable, ctx: Context[T]): TerminationDirective = {
      stopTime()
      ctx.fail(cause)
    }
    override def onUpstreamFinish(ctx: Context[T]): TerminationDirective = {
      stopTime()
      ctx.finish()
    }
    private def stopTime() {
      val d = timedContext.stop()
      _onComplete(d)
    }

  }

  final class TimedInterval[T](matching: T ⇒ Boolean, onInterval: FiniteDuration ⇒ Unit) extends PushStage[T, T] {
    private var prevNanos = 0L
    private var matched = 0L

    override def onPush(elem: T, ctx: Context[T]): SyncDirective = {
      if (matching(elem)) {
        val d = updateInterval(elem)

        if (matched > 1)
          onInterval(d)
      }
      ctx.push(elem)
    }

    private def updateInterval(in: T): FiniteDuration = {
      matched += 1
      val nowNanos = System.nanoTime()
      val d = nowNanos - prevNanos
      prevNanos = nowNanos
      d.nanoseconds
    }
  }

}
