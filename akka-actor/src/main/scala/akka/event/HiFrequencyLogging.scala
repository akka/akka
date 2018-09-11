/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.event

import java.util.concurrent.atomic.AtomicLong

import scala.concurrent.duration.FiniteDuration

import akka.actor.ActorSystem
import akka.annotation.InternalApi

/**
 * INTERNAL API
 *
 * Utility to filter log statements, e.g. log at most once every 10 seconds.
 * Useful in places where there is a risk of overwhelming the logging system with too many log messages.
 */
@InternalApi private[akka] class HiFrequencyLogging(system: ActorSystem, maxCalls: Int, within: FiniteDuration) {

  private val count = new AtomicLong
  @volatile private var discarded = 0L

  /**
   * The `logStatement` will be called if the `maxCalls` limit has not been reached. The counter is
   * reset after the `within` duration.
   * @param logStatement function for the log statement to call if the limit has not been reached, the `String`
   *                     parameter is additional information about number of discarded statements which
   *                     can be included at the end of the log message
   * @return `true` if the statement was run
   */
  def filter(logStatement: String ⇒ Unit): Boolean = {
    val n = count.incrementAndGet()
    if (n == 1L) {
      // lazy scheduling for minimal resource consumption when not used
      system.scheduler.scheduleOnce(within) {
        reset()
      }(system.dispatcher)

      val d = discarded
      val discardInfo =
        if (d == 0) ""
        else s" ([$d] similar log messages were discarded in last [${within.toMillis} ms])" // first time after a reset

      logStatement(discardInfo)
      true
    } else if (n <= maxCalls) {
      logStatement("")
      true
    } else {
      // exceeded limit, don't log
      false
    }
  }

  def reset(): Unit =
    discarded = math.max(0L, count.getAndSet(0) - maxCalls)

}
