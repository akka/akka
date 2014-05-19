/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.extra

import akka.stream.scaladsl.{ Duct, Flow }
import scala.collection.immutable
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration._

import scala.language.implicitConversions
import scala.language.existentials
import akka.stream.Transformer

/**
 * Provides operations needed to implement the `timed` DSL
 */
private[akka] trait TimedOps {

  import Timed._

  /**
   * INTERNAL API
   *
   * Measures time from receieving the first element and completion events - one for each subscriber of this `Flow`.
   */
  def timed[I, O](flow: Flow[I], measuredOps: Flow[I] ⇒ Flow[O], onComplete: FiniteDuration ⇒ Unit): Flow[O] = {
    val ctx = new TimedFlowContext

    val startWithTime = flow.transform(new StartTimedFlow(ctx))
    val userFlow = measuredOps(startWithTime)
    userFlow.transform(new StopTimed(ctx, onComplete))
  }

  /**
   * INTERNAL API
   *
   * Measures time from receieving the first element and completion events - one for each subscriber of this `Flow`.
   */
  def timed[I, O, Out](duct: Duct[I, O], measuredOps: Duct[I, O] ⇒ Duct[O, Out], onComplete: FiniteDuration ⇒ Unit): Duct[O, Out] = {
    // todo is there any other way to provide this for Flow / Duct, without duplicating impl? (they don't share any super-type)
    val ctx = new TimedFlowContext

    val startWithTime: Duct[I, O] = duct.transform(new StartTimedFlow(ctx))
    val userFlow: Duct[O, Out] = measuredOps(startWithTime)
    userFlow.transform(new StopTimed(ctx, onComplete))
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
   * Measures rolling interval between immediatly subsequent `matching(o: O)` elements.
   */
  def timedIntervalBetween[O](flow: Flow[O], matching: O ⇒ Boolean, onInterval: FiniteDuration ⇒ Unit): Flow[O] = {
    flow.transform(new TimedIntervalTransformer[O](matching, onInterval))
  }

  /**
   * Measures rolling interval between immediatly subsequent `matching(o: O)` elements.
   */
  def timedIntervalBetween[I, O](duct: Duct[I, O], matching: O ⇒ Boolean, onInterval: FiniteDuration ⇒ Unit): Duct[I, O] = {
    // todo is there any other way to provide this for Flow / Duct, without duplicating impl? (they don't share any super-type)
    duct.transform(new TimedIntervalTransformer[O](matching, onInterval))
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

  final class StartTimedFlow[T](ctx: TimedFlowContext) extends Transformer[T, T] {
    override def name = "startTimed"

    private var started = false

    override def onNext(element: T) = {
      if (!started) {
        ctx.start()
        started = true
      }

      immutable.Seq(element)
    }
  }

  final class StopTimed[T](ctx: TimedFlowContext, _onComplete: FiniteDuration ⇒ Unit) extends Transformer[T, T] {
    override def name = "stopTimed"

    override def onComplete(): immutable.Seq[T] = {
      val d = ctx.stop()
      _onComplete(d)

      Nil
    }

    override def onNext(element: T) = immutable.Seq(element)
  }

  final class TimedIntervalTransformer[T](matching: T ⇒ Boolean, onInterval: FiniteDuration ⇒ Unit) extends Transformer[T, T] {
    override def name = "timedInterval"

    private var prevNanos = 0L
    private var matched = 0L

    override def onNext(in: T): immutable.Seq[T] = {
      if (matching(in)) {
        val d = updateInterval(in)

        if (matched > 1)
          onInterval(d)
      }
      immutable.Seq(in)
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
