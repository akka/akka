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
private[stream] class Throttle[T](cost: Int,
                                  per: FiniteDuration,
                                  maximumBurst: Int,
                                  costCalculation: (T) ⇒ Int,
                                  mode: ThrottleMode)
  extends SimpleLinearGraphStage[T] {
  require(cost > 0, "cost must be > 0")
  require(per.toMillis > 0, "per time must be > 0")
  require(!(mode == ThrottleMode.Enforcing && maximumBurst < 0), "maximumBurst must be > 0 in Enforcing mode")

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) {
    var willStop = false
    var lastTokens: Long = maximumBurst
    var previousTime: Long = now()

    val speed = ((cost.toDouble / per.toNanos) * 1073741824).toLong
    val timerName: String = "ThrottleTimer"

    var currentElement: Option[T] = None

    setHandler(in, new InHandler {
      val scaledMaximumBurst = scale(maximumBurst)

      override def onUpstreamFinish(): Unit =
        if (isAvailable(out) && isTimerActive(timerName)) willStop = true
        else completeStage()

      override def onPush(): Unit = {
        val elem = grab(in)
        val elementCost = scale(costCalculation(elem))

        if (lastTokens >= elementCost) {
          lastTokens -= elementCost
          push(out, elem)
        } else {
          val currentTime = now()
          val currentTokens = Math.min((currentTime - previousTime) * speed + lastTokens, scaledMaximumBurst)
          if (currentTokens < elementCost)
            mode match {
              case Shaping ⇒
                currentElement = Some(elem)
                val waitTime = (elementCost - currentTokens) / speed
                previousTime = currentTime + waitTime
                scheduleOnce(timerName, waitTime.nanos)
              case Enforcing ⇒ failStage(new RateExceededException("Maximum throttle throughput exceeded"))
            }
          else {
            lastTokens = currentTokens - elementCost
            previousTime = currentTime
            push(out, elem)
          }
        }
      }
    })

    override protected def onTimer(key: Any): Unit = {
      push(out, currentElement.get)
      currentElement = None
      lastTokens = 0
      if (willStop) completeStage()
    }

    setHandler(out, new OutHandler {
      override def onPull(): Unit = pull(in)
    })

    override def preStart(): Unit = previousTime = now()

    private def now(): Long = System.nanoTime()

    private def scale(e: Int): Long = e.toLong << 30
  }

  override def toString = "Throttle"
}