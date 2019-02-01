/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal
import akka.actor.typed.scaladsl.{ AbstractBehavior, ActorContext }
import akka.actor.typed._
import akka.annotation.InternalApi

/**
 * INTERNAL API
 */
@InternalApi
private[akka] final class RouterPoolImpl[T](ctx: ActorContext[T], poolSize: Int, behavior: Behavior[T]) extends AbstractBehavior[T] {
  if (poolSize < 1) throw new IllegalArgumentException(s"pool size must be positive, was $poolSize")

  private var children = (1 to poolSize).map { n ⇒
    val name = s"pool-child-$n"
    val child = ctx.spawn(behavior, name)
    ctx.watch(child)
    child
  }.toArray
  private var nextChildIdx = 0

  def onMessage(msg: T): Behavior[T] = {
    val recipient = children(nextChildIdx)
    recipient.tell(msg)
    nextChildIdx += 1
    if (nextChildIdx >= children.length) nextChildIdx = 0
    Behavior.same
  }

  override def onSignal: PartialFunction[Signal, Behavior[T]] = {
    case Terminated(child) ⇒
      ctx.log.warning(s"Pool child stopped [${child.path}]")
      val childIdx = children.indexOf(child)
      children = children.filterNot(_ == child)
      if (nextChildIdx < childIdx)
        nextChildIdx -= 1
      if (nextChildIdx >= children.length)
        nextChildIdx = 0
      if (children.isEmpty) Behavior.stopped
      else Behavior.same
  }

}
