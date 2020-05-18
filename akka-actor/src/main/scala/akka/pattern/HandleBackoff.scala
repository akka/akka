/*
 * Copyright (C) 2018-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.pattern

import akka.actor.{ Actor, ActorRef, Props }
import akka.annotation.InternalApi

/**
 * INTERNAL API
 *
 * Implements basic backoff handling for [[internal.BackoffOnRestartSupervisor]] and [[internal.BackoffOnStopSupervisor]].
 */
@InternalApi private[akka] trait HandleBackoff {
  this: Actor =>
  def childProps: Props
  def childName: String
  def reset: BackoffReset
  protected def handleMessageToChild(m: Any): Unit

  private var handler: Option[ActorRef] = None
  var child: Option[ActorRef] = None
  var restartCount = 0
  var finalStopMessageReceived = false

  import BackoffSupervisor._
  import context.dispatcher

  override def preStart(): Unit = startChild()

  def startChild(): Unit = if (child.isEmpty) {
    child = Some(context.watch(context.actorOf(childProps, childName)))
  }

  protected def getOrCreateHandler(p: Props): ActorRef = handler match {
    case Some(h) =>
      h
    case None =>
      val h = context.actorOf(p)
      handler = Some(h)
      h
  }

  def handleBackoff: Actor.Receive = {
    case StartChild =>
      startChild()
      reset match {
        case AutoReset(resetBackoff) =>
          if (restartCount > 0)
            context.system.scheduler.scheduleOnce(resetBackoff, self, ResetRestartCount(restartCount))
        case _ => // ignore
      }

    case Reset =>
      reset match {
        case ManualReset => restartCount = 0
        case msg         => unhandled(msg)
      }

    case ResetRestartCount(seenRestartCount) =>
      if (seenRestartCount == restartCount && restartCount > 0) {
        restartCount = 0
      }

    case GetRestartCount =>
      sender() ! RestartCount(restartCount)

    case GetCurrentChild =>
      sender() ! CurrentChild(child)

    case msg if child.contains(sender()) =>
      // use the BackoffSupervisor as sender
      context.parent ! msg

    case msg =>
      handleMessageToChild(msg)
  }
}
