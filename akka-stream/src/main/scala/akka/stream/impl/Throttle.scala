/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.stream.ThrottleMode.{ Enforcing, Shaping }
import akka.stream.impl.fusing.GraphStages.SimpleLinearGraphStage
import akka.stream.stage._
import akka.stream._

import scala.concurrent.duration.{ FiniteDuration, _ }
import scala.util.control.NonFatal

/**
 * INTERNAL API
 */
private[stream] class Throttle[T](cost: Int,
                                  per: FiniteDuration,
                                  maximumBurst: Int,
                                  costCalculation: (T) ⇒ Int,
                                  mode: ThrottleMode)
  extends SimpleLinearGraphStage[T] {

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) {
    var willStop = false
    var lastTokens: Long = 0
    var previousTime: Long = 0

    val speed = ((cost.toDouble / per.toMillis) * 1024 * 1024).toLong
    val timerName: String = "ThrottleTimer"

    var currentElement: Option[T] = None

    setHandler(in, new InHandler {
      val scaledMaximumBurst = scale(maximumBurst)

      override def onUpstreamFinish(): Unit =
        if (isAvailable(out) && isTimerActive(timerName)) willStop = true
        else completeStage()

      override def onPush(): Unit = {
        val timeElapsed = now() - previousTime
        val currentTokens = Math.min(timeElapsed * speed + lastTokens, scaledMaximumBurst)
        val elem = grab(in)
        val elementCost = scale(costCalculation(elem))
        if (currentTokens < elementCost)
          mode match {
            case Shaping ⇒
              currentElement = Some(elem)
              scheduleOnce(timerName, ((elementCost - currentTokens) / speed).millis)
            case Enforcing ⇒ failStage(new RateExceededException("Maximum throttle throughput exceeded"))
          }
        else {
          lastTokens = currentTokens - elementCost
          previousTime = now()
          push(out, elem)
        }
      }
    })

    override protected def onTimer(key: Any): Unit = {
      push(out, currentElement.get)
      currentElement = None
      previousTime = now()
      lastTokens = 0
      if (willStop) completeStage()
    }

    setHandler(out, new OutHandler {
      override def onPull(): Unit = pull(in)
    })

    override def preStart(): Unit = previousTime = now()

    private def now(): Long = System.currentTimeMillis()

    private def scale(e: Int): Long = e.toLong << 20
  }

  override def toString = "Throttle"
}