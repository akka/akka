/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.extra

import akka.stream.scaladsl.{ Transformer, Flow }
import scala.collection.immutable
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration._

import scala.language.implicitConversions
import scala.language.existentials

/**
 * Provides operations needed to implement the `timed` DSL
 */
private[akka] trait TimedOps {

  import Timed._

  /**
   * Measures time from inputing the first element and emiting the last element by the contained flow.
   */
  def timed[O](flow: Flow[O], measuredOps: Flow[O] ⇒ Flow[O], onComplete: Duration ⇒ Unit): Flow[O] = {
    val ctx = new TimedFlowContext

    val startWithTime = flow.transform(new StartTimedFlowContextTransformer(ctx)).asInstanceOf[Flow[O]]
    val userFlow = measuredOps(startWithTime)
    val done = userFlow.transform(new StopTimedFlowContextTransformer(ctx, onComplete))
    done.asInstanceOf[Flow[O]]
  }

}

/**
 * Provides operations needed to implement the `timedIntervalBetween` DSL
 */
private[akka] trait TimedIntervalBetweenOps {
  /**
   * Measures rolling interval between `matching(o: O)` elements.
   */
  def timedIntervalBetween[O](flow: Flow[O], matching: O ⇒ Boolean, onInterval: Duration ⇒ Unit): Flow[O] = {
    flow.transform(new Transformer[O, O] {

      private var prevNanos = 0L
      private var matched = 0L

      override def onNext(in: O): immutable.Seq[O] = {
        if (matching(in)) {
          val d = updateInterval(in)

          if (matched > 1)
            onInterval(d)
        }
        immutable.Seq(in)
      }

      private def updateInterval(in: O): FiniteDuration = {
        matched += 1
        val nowNanos = System.nanoTime()
        val d = nowNanos - prevNanos
        prevNanos = nowNanos
        d.nanoseconds
      }
    })
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

    def stop(): Duration = {
      _stop.compareAndSet(0, System.nanoTime())
      compareStartAndStop()
    }

    private def compareStartAndStop(): Duration = _stop.get match {
      case 0         ⇒ Duration.Undefined
      case stopNanos ⇒ (stopNanos - _start.get).nanos
    }
  }

  final class StartTimedFlowContextTransformer(ctx: TimedFlowContext) extends Transformer[Any, Any] {

    private var started = false

    override def onNext(element: Any) = {
      if (!started) {
        ctx.start()
        started = true
      }

      immutable.Seq(element)
    }
  }

  final class StopTimedFlowContextTransformer(ctx: TimedFlowContext, _onComplete: Duration ⇒ Unit) extends Transformer[Any, Any] {

    var stopped = false

    override def onComplete(): immutable.Seq[Any] = {
      if (!stopped) {
        val d = ctx.stop()
        _onComplete(d)
      }

      Nil
    }

    override def onNext(element: Any) = immutable.Seq(element)
  }

}
