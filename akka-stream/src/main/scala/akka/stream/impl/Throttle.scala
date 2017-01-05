/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl

import akka.stream.ThrottleMode.{ Enforcing, Shaping }
import akka.stream.impl.fusing.GraphStages.SimpleLinearGraphStage
import akka.stream.stage._
import akka.stream._
import akka.util.NanoTimeTokenBucket

import scala.concurrent.duration.{ FiniteDuration, _ }

/**
 * INTERNAL API
 */
class Throttle[T](
  val cost:            Int,
  val per:             FiniteDuration,
  val maximumBurst:    Int,
  val costCalculation: (T) ⇒ Int,
  val mode:            ThrottleMode)
  extends SimpleLinearGraphStage[T] {
  require(cost > 0, "cost must be > 0")
  require(per.toNanos > 0, "per time must be > 0")
  require(!(mode == ThrottleMode.Enforcing && maximumBurst < 0), "maximumBurst must be > 0 in Enforcing mode")
  require(per.toNanos >= cost, "Rates larger than 1 unit / nanosecond are not supported")

  // There is some loss of precision here because of rounding, but this only happens if nanosBetweenTokens is very
  // small which is usually at rates where that precision is highly unlikely anyway as the overhead of this stage
  // is likely higher than the required accuracy interval.
  private val nanosBetweenTokens = per.toNanos / cost
  private val timerName: String = "ThrottleTimer"

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) {
    private val tokenBucket = new NanoTimeTokenBucket(maximumBurst, nanosBetweenTokens)

    var willStop = false
    var currentElement: T = _
    val enforcing = mode match {
      case Enforcing ⇒ true
      case Shaping   ⇒ false
    }

    override def preStart(): Unit = tokenBucket.init()

    // This scope is here just to not retain an extra reference to the handler below.
    // We can't put this code into preRestart() because setHandler() must be called before that.
    {
      val handler = new InHandler with OutHandler {
        override def onUpstreamFinish(): Unit =
          if (isAvailable(out) && isTimerActive(timerName)) willStop = true
          else completeStage()

        override def onPush(): Unit = {
          val elem = grab(in)
          val cost = costCalculation(elem)
          val delayNanos = tokenBucket.offer(cost)

          if (delayNanos == 0L) push(out, elem)
          else {
            if (enforcing) failStage(new RateExceededException("Maximum throttle throughput exceeded."))
            else {
              currentElement = elem
              scheduleOnce(timerName, delayNanos.nanos)
            }
          }
        }

        override def onPull(): Unit = pull(in)
      }

      setHandler(in, handler)
      setHandler(out, handler)
      // After this point, we no longer need the `handler` so it can just fall out of scope.
    }

    override protected def onTimer(key: Any): Unit = {
      push(out, currentElement)
      currentElement = null.asInstanceOf[T]
      if (willStop) completeStage()
    }

  }

  override def toString = "Throttle"
}
