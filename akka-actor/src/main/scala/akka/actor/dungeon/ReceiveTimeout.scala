/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.dungeon

import akka.actor.ActorCell
import akka.actor.Cancellable
import akka.actor.NotInfluenceReceiveTimeout

import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration

private[akka] object ReceiveTimeout {
  import ActorCell.emptyCancellable

  private final val empty: ReceiveTimeoutValue =
    ReceiveTimeoutValue(Duration.Undefined, emptyCancellable, Duration.Undefined)

  final case class ReceiveTimeoutValue(current: Duration, task: Cancellable, previous: Duration) {

    def :+(latest: Duration): ReceiveTimeoutValue = latest match {
      case null => this // user input check
      case _ =>
        val prev = current
        copy(previous = prev, current = latest)
    }

    /** Returns true if `current` is different from `previous` duration. */
    def changed: Boolean = current ne previous

    def clear(): ReceiveTimeoutValue = copy(task = emptyCancellable)

    /* read-only functions */
    def nonEmpty: Boolean = this ne empty
    def taskIsEmpty: Boolean = task eq emptyCancellable
    def taskNonEmpty: Boolean = !taskIsEmpty
  }

  object ReceiveTimeoutValue {
    def apply(): ReceiveTimeoutValue = empty

    def apply(duration: FiniteDuration, task: Cancellable): ReceiveTimeoutValue =
      ReceiveTimeoutValue(duration, task, Duration.Undefined)
  }
}

private[akka] trait ReceiveTimeout { this: ActorCell =>

  import ReceiveTimeout._

  private var _receiveTimeout: ReceiveTimeoutValue = ReceiveTimeoutValue()

  final def receiveTimeout: Duration = _receiveTimeout.current

  final def setReceiveTimeout(timeout: Duration): Unit = _receiveTimeout :+= timeout

  protected def rescheduleOrCancelReceiveTimeoutIfNeeded(message: Any): Unit =
    if (_receiveTimeout.nonEmpty)
      rescheduleOrCancelReceiveTimeout(shouldInfluenceReceiveTimeout(message))

  protected final def rescheduleOrCancelReceiveTimeout(reschedule: Boolean): Unit =
    _receiveTimeout.current match {
      case f: FiniteDuration =>
        // The fact that timeout is FiniteDuration and task is emptyCancellable
        // means that a user called `context.setReceiveTimeout(...)`
        // while sending the ReceiveTimeout message is not scheduled yet.
        // We have to handle the case and schedule sending the ReceiveTimeout message
        // ignoring the reschedule parameter.
        if (reschedule || _receiveTimeout.taskIsEmpty)
          rescheduleReceiveTimeout(f)

      case _ => cancelReceiveTimeout()
    }

  private def rescheduleReceiveTimeout(f: FiniteDuration): Unit = {
    _receiveTimeout.task.cancel() //Cancel any ongoing future
    val task = system.scheduler.scheduleOnce(f, self, akka.actor.ReceiveTimeout)(this.dispatcher)
    _receiveTimeout = ReceiveTimeoutValue(f, task)
  }

  protected def cancelReceiveTimeoutIfNeeded(message: Any): Unit =
    if (_receiveTimeout.nonEmpty && shouldInfluenceReceiveTimeout(message))
      cancelReceiveTimeout()

  override final def cancelReceiveTimeout(): Unit =
    if (_receiveTimeout.taskNonEmpty) {
      _receiveTimeout.task.cancel()
      _receiveTimeout = _receiveTimeout.clear()
    }

  /** Returns true if `receiveTimeout` is changed while handling a `NotInfluenceReceiveTimeout`. */
  protected final def changedDuringNotInfluence(message: Any): Boolean =
    _receiveTimeout.changed && !shouldInfluenceReceiveTimeout(message)

  /** Returns true if the message is not a `NotInfluenceReceiveTimeout`
   * and should not reset the receive timeout.
   */
  private def shouldInfluenceReceiveTimeout(message: Any): Boolean = {
    // may shave a few ms off: !message.isInstanceOf[NotInfluenceReceiveTimeout]
    message match {
      case _: NotInfluenceReceiveTimeout => false
      case _                             => true
    }
  }
}
