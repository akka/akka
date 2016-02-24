/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl

import akka.stream.ThrottleMode.{ Enforcing, Shaping }
import akka.stream.impl.fusing.GraphStages.SimpleLinearGraphStage
import akka.stream.stage._
import akka.stream._

import scala.concurrent.duration.{ FiniteDuration, _ }

/**
 * INTERNAL API
 */
private[stream] object Throttle {

  val miniTokenBits = 30

  private def tokenToMiniToken(e: Int): Long = e.toLong << Throttle.miniTokenBits
}

/**
 * INTERNAL API
 */
/*
 * This class tracks a token bucket in an efficient way.
 *
 * For accuracy, instead of tracking integer tokens the implementation tracks "miniTokens" which are 1/2^30 fraction
 * of a token. This allows us to track token replenish rate as miniTokens/nanosecond which allows us to use simple
 * arithmetic without division and also less inaccuracy due to rounding on token count caculation.
 *
 * The replenish amount, and hence the current time is only queried if the bucket does not hold enough miniTokens, in
 * other words, replenishing the bucket is *on-need*. In addition, to compensate scheduler inaccuracy, the implementation
 * calculates the ideal "previous time" explicitly, not relying on the scheduler to tick at that time. This means that
 * when the scheduler actually ticks, some time has been elapsed since the calculated ideal tick time, and those tokens
 * are added to the bucket as any calculation is always relative to the ideal tick time.
 *
 */
private[stream] class Throttle[T](cost: Int,
                                  per: FiniteDuration,
                                  maximumBurst: Int,
                                  costCalculation: (T) ⇒ Int,
                                  mode: ThrottleMode)
  extends SimpleLinearGraphStage[T] {
  require(cost > 0, "cost must be > 0")
  require(per.toMillis > 0, "per time must be > 0")
  require(!(mode == ThrottleMode.Enforcing && maximumBurst < 0), "maximumBurst must be > 0 in Enforcing mode")

  private val maximumBurstMiniTokens = Throttle.tokenToMiniToken(maximumBurst)
  private val miniTokensPerNanos = (Throttle.tokenToMiniToken(cost).toDouble / per.toNanos).toLong
  private val timerName: String = "ThrottleTimer"

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) {
    var willStop = false
    var previousMiniTokens: Long = maximumBurstMiniTokens
    var previousNanos: Long = System.nanoTime()

    var currentElement: Option[T] = None

    setHandler(in, new InHandler {

      override def onUpstreamFinish(): Unit =
        if (isAvailable(out) && isTimerActive(timerName)) willStop = true
        else completeStage()

      override def onPush(): Unit = {
        val elem = grab(in)
        val elementCostMiniTokens = Throttle.tokenToMiniToken(costCalculation(elem))

        if (previousMiniTokens >= elementCostMiniTokens) {
          previousMiniTokens -= elementCostMiniTokens
          push(out, elem)
        } else {
          val currentNanos = System.nanoTime()
          val currentMiniTokens = Math.min(
            (currentNanos - previousNanos) * miniTokensPerNanos + previousMiniTokens,
            maximumBurstMiniTokens)

          if (currentMiniTokens < elementCostMiniTokens)
            mode match {
              case Shaping ⇒
                currentElement = Some(elem)
                val waitNanos = (elementCostMiniTokens - currentMiniTokens) / miniTokensPerNanos
                previousNanos = currentNanos + waitNanos
                scheduleOnce(timerName, waitNanos.nanos)
              case Enforcing ⇒ failStage(new RateExceededException("Maximum throttle throughput exceeded"))
            }
          else {
            previousMiniTokens = currentMiniTokens - elementCostMiniTokens
            previousNanos = currentNanos
            push(out, elem)
          }
        }
      }
    })

    override protected def onTimer(key: Any): Unit = {
      push(out, currentElement.get)
      currentElement = None
      previousMiniTokens = 0
      if (willStop) completeStage()
    }

    setHandler(out, new OutHandler {
      override def onPull(): Unit = pull(in)
    })

    override def preStart(): Unit = previousNanos = System.nanoTime()

  }

  override def toString = "Throttle"
}