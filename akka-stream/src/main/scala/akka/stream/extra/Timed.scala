/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.extra

import scala.collection.immutable
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration._
import scala.language.implicitConversions
import scala.language.existentials
import akka.stream.scaladsl.OperationAttributes._
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.Flow
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

    val startTimed = (f: Flow[I, I, Unit]) ⇒ f.transform(() ⇒ new StartTimedFlow(ctx))
    val stopTimed = (f: Flow[O, O, Unit]) ⇒ f.transform(() ⇒ new StopTimed(ctx, onComplete))

    val begin = source.section(name("startTimed"), (originalMat: Mat, _: Unit) ⇒ originalMat)(startTimed)
    measuredOps(begin).section(name("stopTimed"), (originalMat: Mat2, _: Unit) ⇒ originalMat)(stopTimed)
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

    val startTimed = (f: Flow[O, O, Unit]) ⇒ f.transform(() ⇒ new StartTimedFlow(ctx))
    val stopTimed = (f: Flow[Out, Out, Unit]) ⇒ f.transform(() ⇒ new StopTimed(ctx, onComplete))

    val begin: Flow[I, O, Mat] = flow.section(name("startTimed"), (originalMat: Mat, _: Unit) ⇒ originalMat)(startTimed)
    measuredOps(begin).section(name("stopTimed"), (originalMat: Mat2, _: Unit) ⇒ originalMat)(stopTimed)
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
    source.section(name("timedInterval"), (originalMat: Mat, _: Unit) ⇒ originalMat) {
      _.transform(() ⇒ new TimedIntervalTransformer[O](matching, onInterval))
    }
  }

  /**
   * Measures rolling interval between immediately subsequent `matching(o: O)` elements.
   */
  def timedIntervalBetween[I, O, Mat](flow: Flow[I, O, Mat], matching: O ⇒ Boolean, onInterval: FiniteDuration ⇒ Unit): Flow[I, O, Mat] = {
    // todo is there any other way to provide this for Flow / Duct, without duplicating impl?
    // they do share a super-type (FlowOps), but all operations of FlowOps return path dependant type
    flow.section(name("timedInterval"), (originalMat: Mat, _: Unit) ⇒ originalMat) {
      _.transform(() ⇒ new TimedIntervalTransformer[O](matching, onInterval))
    }
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

  final class StartTimedFlow[T](timedContext: TimedFlowContext) extends PushStage[T, T] {
    private var started = false

    override def onPush(elem: T, ctx: Context[T]): Directive = {
      if (!started) {
        timedContext.start()
        started = true
      }
      ctx.push(elem)
    }
  }

  final class StopTimed[T](timedContext: TimedFlowContext, _onComplete: FiniteDuration ⇒ Unit) extends PushStage[T, T] {

    override def onPush(elem: T, ctx: Context[T]): Directive = ctx.push(elem)

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

  final class TimedIntervalTransformer[T](matching: T ⇒ Boolean, onInterval: FiniteDuration ⇒ Unit) extends PushStage[T, T] {
    private var prevNanos = 0L
    private var matched = 0L

    override def onPush(elem: T, ctx: Context[T]): Directive = {
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
