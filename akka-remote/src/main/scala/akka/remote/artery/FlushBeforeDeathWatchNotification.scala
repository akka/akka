/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery

import java.util.concurrent.atomic.AtomicLong

import scala.concurrent.Promise
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

import akka.Done
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.Props
import akka.annotation.InternalApi

/**
 * INTERNAL API
 */
@InternalApi
private[remote] object FlushBeforeDeathWatchNotification {
  private val nameCounter = new AtomicLong(0L)

  def props(done: Promise[Done], timeout: FiniteDuration, association: Association): Props = {
    Props(new FlushBeforeDeathWatchNotification(done, timeout, association))
  }

  def nextName(): String = s"flush-${nameCounter.incrementAndGet()}"

  private case object Timeout
}

/**
 * INTERNAL API
 */
@InternalApi
private[remote] class FlushBeforeDeathWatchNotification(
    done: Promise[Done],
    timeout: FiniteDuration,
    association: Association)
    extends Actor
    with ActorLogging {
  import FlushBeforeDeathWatchNotification.Timeout

  var remaining = 0

  private val timeoutTask =
    context.system.scheduler.scheduleOnce(timeout, self, Timeout)(context.dispatcher)

  override def preStart(): Unit = {
    try {
      remaining = association.sendFlush(self, excludeControlQueue = true)
      if (remaining == 0) {
        done.trySuccess(Done)
        context.stop(self)
      }
    } catch {
      case NonFatal(e) =>
        // sendFlush may throw
        done.tryFailure(e)
        // will log and stop
        throw e
    }
  }

  override def postStop(): Unit = {
    timeoutTask.cancel()
    done.trySuccess(Done)
  }

  def receive: Receive = {
    case FlushAck =>
      remaining -= 1
      log.debug("Flush acknowledged, [{}] remaining", remaining)
      if (remaining == 0)
        context.stop(self)
    case Timeout =>
      log.debug("Flush timeout, [{}] remaining", remaining)
      context.stop(self)
  }
}
