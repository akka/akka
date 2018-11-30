/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import akka.actor.ActorRef
import akka.actor.DeadLetter
import akka.actor.StashOverflowException
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.StashBuffer
import akka.annotation.InternalApi
import akka.persistence.DiscardToDeadLetterStrategy
import akka.persistence.ReplyToStrategy
import akka.persistence.ThrowOverflowExceptionStrategy
import akka.util.ConstantFun
import akka.{ actor ⇒ untyped }

/** INTERNAL API: Stash management for persistent behaviors */
@InternalApi
private[akka] trait StashManagement[C, E, S] {
  import akka.actor.typed.scaladsl.adapter._

  def setup: BehaviorSetup[C, E, S]

  private def context: ActorContext[InternalProtocol] = setup.context

  private def stashState: StashState = setup.stashState

  protected def isInternalStashEmpty: Boolean = stashState.internalStashBuffer.isEmpty

  /**
   * Stash a command to the internal stash buffer, which is used while waiting for persist to be completed.
   */
  protected def stashInternal(msg: InternalProtocol): Unit =
    stash(msg, stashState.internalStashBuffer)

  /**
   * Stash a command to the user stash buffer, which is used when `Stash` effect is used.
   */
  protected def stashUser(msg: InternalProtocol): Unit =
    stash(msg, stashState.userStashBuffer)

  private def stash(msg: InternalProtocol, buffer: StashBuffer[InternalProtocol]): Unit = {
    logStashMessage(msg, buffer)

    try buffer.stash(msg) catch {
      case e: StashOverflowException ⇒
        setup.stashOverflowStrategy match {
          case DiscardToDeadLetterStrategy ⇒
            val noSenderBecauseAkkaTyped: ActorRef = untyped.ActorRef.noSender
            context.system.deadLetters.tell(DeadLetter(msg, noSenderBecauseAkkaTyped, context.self.toUntyped))

          case ReplyToStrategy(_) ⇒
            throw new RuntimeException("ReplyToStrategy does not make sense at all in Akka Typed, since there is no sender()!")

          case ThrowOverflowExceptionStrategy ⇒
            throw e
        }
    }
  }

  /**
   * `tryUnstashOne` is called at the end of processing each command or when persist is completed
   */
  protected def tryUnstashOne(behavior: Behavior[InternalProtocol]): Behavior[InternalProtocol] = {
    val buffer =
      if (stashState.isUnstashAllInProgress) stashState.userStashBuffer
      else stashState.internalStashBuffer

    if (buffer.nonEmpty) {
      logUnstashMessage(buffer)

      stashState.decrementUnstashAllProgress()

      buffer.unstash(setup.context, behavior, 1, ConstantFun.scalaIdentityFunction)
    } else behavior

  }

  /**
   * Subsequent `tryUnstashOne` will drain the user stash buffer before using the
   * internal stash buffer. It will unstash as many commands as are in the buffer when
   * `unstashAll` was called, i.e. if subsequent commands stash more, those will
   * not be unstashed until `unstashAll` is called again.
   */
  protected def unstashAll(): Unit = {
    if (stashState.userStashBuffer.nonEmpty) {
      logUnstashAll()
      stashState.startUnstashAll()
      // tryUnstashOne is called from EventSourcedRunning at the end of processing each command
      // or when persist is completed
    }
  }

  protected def isUnstashAllInProgress: Boolean =
    stashState.isUnstashAllInProgress

  private def logStashMessage(msg: InternalProtocol, buffer: StashBuffer[InternalProtocol]): Unit = {
    if (setup.settings.logOnStashing) setup.log.debug(
      "Stashing message to {} stash: [{}] ",
      if (buffer eq stashState.internalStashBuffer) "internal" else "user", msg)
  }

  private def logUnstashMessage(buffer: StashBuffer[InternalProtocol]): Unit = {
    if (setup.settings.logOnStashing) setup.log.debug(
      "Unstashing message from {} stash: [{}]",
      if (buffer eq stashState.internalStashBuffer) "internal" else "user", buffer.head)
  }

  private def logUnstashAll(): Unit = {
    if (setup.settings.logOnStashing) setup.log.debug(
      "Unstashing all [{}] messages from user stash, first is: [{}]",
      stashState.userStashBuffer.size, stashState.userStashBuffer.head)
  }

}

/** INTERNAL API: stash buffer state in order to survive restart of internal behavior */
@InternalApi
private[akka] class StashState(settings: EventSourcedSettings) {

  private var _internalStashBuffer: StashBuffer[InternalProtocol] = StashBuffer(settings.stashCapacity)
  private var _userStashBuffer: StashBuffer[InternalProtocol] = StashBuffer(settings.stashCapacity)
  private var unstashAllInProgress = 0

  def internalStashBuffer: StashBuffer[InternalProtocol] = _internalStashBuffer

  def userStashBuffer: StashBuffer[InternalProtocol] = _userStashBuffer

  def clearStashBuffers(): Unit = {
    _internalStashBuffer = StashBuffer(settings.stashCapacity)
    _userStashBuffer = StashBuffer(settings.stashCapacity)
    unstashAllInProgress = 0
  }

  def isUnstashAllInProgress: Boolean =
    unstashAllInProgress > 0

  def decrementUnstashAllProgress(): Unit = {
    if (isUnstashAllInProgress)
      unstashAllInProgress -= 1
  }

  def startUnstashAll(): Unit =
    unstashAllInProgress = _userStashBuffer.size

}
