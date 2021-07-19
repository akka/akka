/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.dispatch

import scala.concurrent.duration._

import akka.actor.ActorSystem
import akka.actor.ClassicActorSystemProvider
import akka.actor.ExtendedActorSystem
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider
import akka.event.Logging

object SuspendDetector extends ExtensionId[SuspendDetector] with ExtensionIdProvider {
  override def get(system: ActorSystem): SuspendDetector = super.get(system)

  override def get(system: ClassicActorSystemProvider): SuspendDetector = super.get(system)

  override def lookup = SuspendDetector

  override def createExtension(system: ExtendedActorSystem): SuspendDetector = new SuspendDetector(system)

  /**
   * Published to the ActorSystem's eventStream when the process suspension has been detected.
   * Note that this message could be stale when it is received and additional check with
   * [[SuspendDetected#wasSuspended]] is recommended to be sure that the suspension occurred recently.
   */
  final class SuspendDetected(suspendDetectedNanoTime: Long) {
    def wasSuspended(since: FiniteDuration): Boolean =
      (System.nanoTime() - suspendDetectedNanoTime <= since.toNanos)
  }

}

class SuspendDetector(val system: ExtendedActorSystem) extends Extension {
  import SuspendDetector.SuspendDetected

  // FIXME config
  private val tickInterval = 100.millis
  private val tickDeadlineNanos = 5.seconds.toNanos // FIXME default should be > 30 seconds
  
  private val log = Logging(system, classOf[SuspendDetector])

  @volatile private var aliveTime = System.nanoTime()
  @volatile private var suspendDetectedTime = aliveTime - 1.day.toNanos

  system.scheduler.scheduleWithFixedDelay(tickInterval, tickInterval) { () =>
    checkTime()
  }(system.dispatcher)

  private def checkTime(): Boolean = synchronized {
    val now = System.nanoTime()
    val suspendDetected =
      if (now - aliveTime >= tickDeadlineNanos) {
        suspendDetectedTime = now
        true
      } else {
        false
      }

    if (suspendDetected) {
      log.warning("Process was suspended for [{} seconds]", (now - aliveTime).nanos.toSeconds)
      system.eventStream.publish(new SuspendDetected(now))
    }

    aliveTime = now

    suspendDetected
  }

  def wasSuspended(since: FiniteDuration): Boolean = {
    checkTime() || (System.nanoTime() - suspendDetectedTime <= since.toNanos)
  }

}
