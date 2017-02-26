/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed
package patterns

import scala.reflect.ClassTag
import scala.util.control.NonFatal
import akka.event.Logging
import akka.typed.scaladsl.Actor._

/**
 * Simple supervision strategy that restarts the underlying behavior for all
 * failures of type Thr.
 *
 * FIXME add limited restarts and back-off (with limited buffering or vacation responder)
 * FIXME write tests that ensure that restart is canonicalizing PreStart result correctly
 */
final case class Restarter[T, Thr <: Throwable: ClassTag](initialBehavior: Behavior[T], resume: Boolean)(
  behavior: Behavior[T] = initialBehavior) extends Behavior[T] {

  private def restart(ctx: ActorContext[T]): Behavior[T] = {
    try behavior.management(ctx, PreRestart) catch { case NonFatal(_) ⇒ }
    Behavior.canonicalize(initialBehavior.management(ctx, PreStart), initialBehavior)
  }

  private def canonical(b: Behavior[T]): Behavior[T] =
    if (Behavior.isUnhandled(b)) Unhandled
    else if (b eq Behavior.sameBehavior) Same
    else if (!Behavior.isAlive(b)) Stopped
    else if (b eq behavior) Same
    else Restarter[T, Thr](initialBehavior, resume)(b)

  override def management(ctx: ActorContext[T], signal: Signal): Behavior[T] = {
    val b =
      try behavior.management(ctx, signal)
      catch {
        case ex: Thr ⇒
          ctx.system.eventStream.publish(Logging.Error(ex, ctx.self.toString, behavior.getClass, ex.getMessage))
          if (resume) behavior else restart(ctx)
      }
    canonical(b)
  }

  override def message(ctx: ActorContext[T], msg: T): Behavior[T] = {
    val b =
      try behavior.message(ctx, msg)
      catch {
        case ex: Thr ⇒
          ctx.system.eventStream.publish(Logging.Error(ex, ctx.self.toString, behavior.getClass, ex.getMessage))
          if (resume) behavior else restart(ctx)
      }
    canonical(b)
  }
}

/**
 * Simple supervision strategy that restarts the underlying behavior for all
 * failures of type Thr.
 *
 * FIXME add limited restarts and back-off (with limited buffering or vacation responder)
 * FIXME write tests that ensure that all Behaviors are okay with getting PostRestart as first signal
 */
final case class MutableRestarter[T, Thr <: Throwable: ClassTag](initialBehavior: Behavior[T], resume: Boolean) extends Behavior[T] {

  private[this] var current = initialBehavior

  private def restart(ctx: ActorContext[T]): Behavior[T] = {
    try current.management(ctx, PreRestart) catch { case NonFatal(_) ⇒ }
    current = initialBehavior
    current.management(ctx, PreStart)
  }

  override def management(ctx: ActorContext[T], signal: Signal): Behavior[T] = {
    val b =
      try current.management(ctx, signal)
      catch {
        case ex: Thr ⇒
          ctx.system.eventStream.publish(Logging.Error(ex, ctx.self.toString, current.getClass, ex.getMessage))
          if (resume) current else restart(ctx)
      }
    current = Behavior.canonicalize(b, current)
    if (Behavior.isAlive(current)) this else Stopped
  }

  override def message(ctx: ActorContext[T], msg: T): Behavior[T] = {
    val b =
      try current.message(ctx, msg)
      catch {
        case ex: Thr ⇒
          ctx.system.eventStream.publish(Logging.Error(ex, ctx.self.toString, current.getClass, ex.getMessage))
          if (resume) current else restart(ctx)
      }
    current = Behavior.canonicalize(b, current)
    if (Behavior.isAlive(current)) this else Stopped
  }
}
