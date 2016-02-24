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
 * INERNAL API
 */
private[stream] object Throttle {

  val tokensRelativeAccuracy = 10

  def calculateTokenScaling(cost: Int, per: FiniteDuration): Long = {
    val logCost = 31 - Integer.numberOfLeadingZeros(cost)
    val logPer = 63 - java.lang.Long.numberOfLeadingZeros(per.toNanos)
    val logSpeed = logCost - logPer
    math.max(tokensRelativeAccuracy - logSpeed, 1)
  }

}

/**
 * INTERNAL API
 */
/*
 * This class tracks a token bucket in an efficient way.
 *
 * For accuracy, instead of tracking integer tokens the implementation tracks "miniTokens" which are a fixed (but calculated)
 * fraction of a token. This allows us to track token replenish rate as miniTokens/nanosecond which allows us to use simple
 * arithmetic without division and also less inaccuracy due to rounding on token count calculation.
 *
 * The replenish amount, and hence the current time is only queried if the bucket does not hold enough miniTokens, in
 * other words, replenishing the bucket is *on-need*. In addition, to compensate scheduler inaccuracy, the implementation
 * calculates the ideal "previous time" explicitly, not relying on the scheduler to tick at that time. This means that
 * when the scheduler actually ticks, some time has been elapsed since the calculated ideal tick time, and those tokens
 * are added to the bucket as any calculation is always relative to the ideal tick time.
 *
 * The miniToken conversion rate dictates what range of target speeds are possible, as the speed in miniToken/nanos
 * must fit into the non-sign 63 bits of Long. This means that if accuracy is B then the allowed rates in token/s
 * are:
 *
 *   10^9 / 2^B < ratePerSecond < 2^(63-B) * 10^9
 *
 * The current implementation sets the resolution B such as that B = log2(ratePerNanos) - tokensRelativeAccuracy
 * We cap the conversion at miniToken = token, i.e. this means that 1 token / nanosecond is the highest rate (theoretically)
 * supported
 *
 */
private[stream] class Throttle[T](cost: Int,
                                  per: FiniteDuration,
                                  maximumBurst: Int,
                                  costCalculation: (T) ⇒ Int,
                                  mode: ThrottleMode)
  extends SimpleLinearGraphStage[T] {
  require(cost > 0, "cost must be > 0")
  require(per.toNanos > 0, "per time must be > 0")
  require(!(mode == ThrottleMode.Enforcing && maximumBurst < 0), "maximumBurst must be > 0 in Enforcing mode")
  require(per.toDays == 0 || (cost.toDouble / per.toDays >= 0.1), "rate must be no slower than 1 unit / 10 days")

  private val tokenScale = Throttle.calculateTokenScaling(cost, per)
  private val maximumBurstMiniTokens = tokenToMiniToken(maximumBurst)
  private val miniTokensPerNanos = (tokenToMiniToken(cost).toDouble / per.toNanos).toLong
  private val timerName: String = "ThrottleTimer"

  private def tokenToMiniToken(e: Int): Long = e.toLong << tokenScale

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
        val elementCost = costCalculation(elem)

        val bitsAvailable = 63 - tokenScale
        val bitsRequired = 31 - Integer.numberOfLeadingZeros(elementCost)
        require(
          bitsAvailable >= bitsRequired,
          "Cannot represent cost within the available resolution. " +
            "This means that the rate is too low compared to the cost of the element.")
        val elementCostMiniTokens = tokenToMiniToken(elementCost)

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