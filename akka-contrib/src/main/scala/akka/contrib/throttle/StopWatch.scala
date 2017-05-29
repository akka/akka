package akka.contrib.throttle

import akka.actor.Actor

import scala.concurrent.duration.{ FiniteDuration, _ }
import language.postfixOps

/**
 * Created by vikas on 8/18/16.
 */

case object Click
case object AverageRate
case class MaxRate(period: FiniteDuration = 1 second)
case object Ticks
case object Reset
case class Purge(period: FiniteDuration)
case class LastPeriodCount(period: FiniteDuration)

case class MaxRateResponse(rate: Int, times: List[FiniteDuration])
case class LastPeriodCountResponse(count: Int)

class StopWatch extends Actor {
  var laps = scala.collection.mutable.ListBuffer.empty[FiniteDuration] //holds times elapsed from start until receipt of each message
  val start = System.nanoTime

  def minimumInterval = {
    var prevDuration = 0.millis
    laps.foldLeft(0.millis) {
      (minInterval, currentDuration) ⇒
        val interval: FiniteDuration = currentDuration - prevDuration
        prevDuration = currentDuration
        if (minInterval > interval) {
          interval
        } else {
          minInterval
        }
    }
  }

  def maxMovingAverageRate(period: FiniteDuration = 1 second) = {
    val movingWindows1: List[List[FiniteDuration]] = movingWindows(period)
    val maxRate = movingWindows1 map { l ⇒ l.size } max
    val maxRateWindow = movingWindows1 find { l ⇒ l.size == maxRate }
    MaxRateResponse(maxRate, maxRateWindow.getOrElse(List()))
  }

  def movingWindows(period: FiniteDuration): List[List[FiniteDuration]] = {
    laps.tails.toList.map { window ⇒ window.collect { case x if x - window.head <= period ⇒ x } toList }
  }

  override def receive: Receive = {
    case Click ⇒
      val nanosElapsed: FiniteDuration = (System.nanoTime - start).nanosecond
      laps += nanosElapsed
    case AverageRate ⇒
      sender() ! laps.toList.length / laps.last.toUnit(SECONDS)
    case MaxRate(period) ⇒
      sender() ! maxMovingAverageRate(period)
    case Ticks ⇒
      sender() ! laps.length
    case Reset ⇒
      laps.clear()
    case Purge(period) ⇒
      val lastTick = laps.last
      laps collect { case x if lastTick - x > period ⇒ x } foreach { i ⇒ laps -= i }
    case LastPeriodCount(period) ⇒
      val nanosElapsed: FiniteDuration = (System.nanoTime - start).nanosecond
      val count = if (laps.isEmpty) { 0 } else {
        val currentTime = (System.nanoTime - start).nanosecond
        laps.reverseIterator collect { case x if currentTime - x <= period ⇒ x } length
      }

      sender() ! LastPeriodCountResponse(count)

  }
}
