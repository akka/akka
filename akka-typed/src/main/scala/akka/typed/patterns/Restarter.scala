/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed
package patterns

import scala.reflect.ClassTag
import scala.util.control.NonFatal
import akka.event.Logging
import akka.typed.Behavior.{ DeferredBehavior, SameBehavior, StoppedBehavior, UnhandledBehavior }
import akka.typed.scaladsl.Actor._

/**
 * Simple supervision strategy that restarts the underlying behavior for all
 * failures of type Thr.
 *
 * FIXME add limited restarts and back-off (with limited buffering or vacation responder)
 */
final case class Restarter[T, Thr <: Throwable: ClassTag](initialBehavior: Behavior[T], resume: Boolean)(
  behavior: Behavior[T] = initialBehavior) extends ExtensibleBehavior[T] {

  private def restart(ctx: ActorContext[T], startedBehavior: Behavior[T]): Behavior[T] = {
    try Behavior.interpretSignal(startedBehavior, ctx, PreRestart) catch { case NonFatal(_) ⇒ }
    // no need to canonicalize, it's done in the calling methods
    Behavior.preStart(initialBehavior, ctx)
  }

  private def canonical(b: Behavior[T], ctx: ActorContext[T]): Behavior[T] =
    if (Behavior.isUnhandled(b)) Unhandled
    else if (b eq Behavior.SameBehavior) Same
    else if (!Behavior.isAlive(b)) Stopped
    else if (b eq behavior) Same
    else {
      b match {
        case d: DeferredBehavior[_] ⇒ canonical(Behavior.undefer(d, ctx), ctx)
        case b                      ⇒ Restarter[T, Thr](initialBehavior, resume)(b)
      }
    }

  private def preStart(b: Behavior[T], ctx: ActorContext[T]): Behavior[T] =
    Behavior.undefer(b, ctx)

  override def management(ctx: ActorContext[T], signal: Signal): Behavior[T] = {
    val startedBehavior = preStart(behavior, ctx)
    val b =
      try {
        Behavior.interpretSignal(startedBehavior, ctx, signal)
      } catch {
        case ex: Thr ⇒
          ctx.system.eventStream.publish(Logging.Error(ex, ctx.self.toString, behavior.getClass, ex.getMessage))
          if (resume) startedBehavior else restart(ctx, startedBehavior)
      }
    canonical(b, ctx)
  }

  override def message(ctx: ActorContext[T], msg: T): Behavior[T] = {
    val startedBehavior = preStart(behavior, ctx)
    val b =
      try {
        Behavior.interpretMessage(startedBehavior, ctx, msg)
      } catch {
        case ex: Thr ⇒
          ctx.system.eventStream.publish(Logging.Error(ex, ctx.self.toString, behavior.getClass, ex.getMessage))
          if (resume) startedBehavior else restart(ctx, startedBehavior)
      }
    canonical(b, ctx)
  }
}

/**
 * Simple supervision strategy that restarts the underlying behavior for all
 * failures of type Thr.
 *
 * FIXME add limited restarts and back-off (with limited buffering or vacation responder)
 * FIXME write tests that ensure that all Behaviors are okay with getting PostRestart as first signal
 */
final case class MutableRestarter[T, Thr <: Throwable: ClassTag](initialBehavior: Behavior[T], resume: Boolean) extends ExtensibleBehavior[T] {

  private[this] var current: Behavior[T] = _
  private def startCurrent(ctx: ActorContext[T]) = {
    // we need to pre-start it the first time we have access to a context
    if (current eq null) current = Behavior.preStart(initialBehavior, ctx)
  }

  private def restart(ctx: ActorContext[T]): Behavior[T] = {
    try Behavior.interpretSignal(current, ctx, PreRestart) catch { case NonFatal(_) ⇒ }
    Behavior.preStart(initialBehavior, ctx)
  }

  override def management(ctx: ActorContext[T], signal: Signal): Behavior[T] = {
    startCurrent(ctx)
    current =
      try {
        Behavior.interpretSignal(current, ctx, signal)
      } catch {
        case ex: Thr ⇒
          ctx.system.eventStream.publish(Logging.Error(ex, ctx.self.toString, current.getClass, ex.getMessage))
          if (resume) current else restart(ctx)
      }
    if (Behavior.isAlive(current)) this else Stopped
  }

  override def message(ctx: ActorContext[T], msg: T): Behavior[T] = {
    startCurrent(ctx)
    current =
      try Behavior.interpretMessage(current, ctx, msg)
      catch {
        case ex: Thr ⇒
          ctx.system.eventStream.publish(Logging.Error(ex, ctx.self.toString, current.getClass, ex.getMessage))
          if (resume) current else restart(ctx)
      }
    if (Behavior.isAlive(current)) this else Stopped
  }
}
