/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed
package patterns

import scala.reflect.ClassTag
import scala.util.control.NonFatal
import akka.event.Logging

/**
 * Simple supervision strategy that restarts the underlying behavior for all
 * failures of type Thr.
 *
 * FIXME add limited restarts and back-off (with limited buffering or vacation responder)
 * FIXME write tests that ensure that all Behaviors are okay with getting PostRestart as first signal
 */
final case class Restarter[T, Thr <: Throwable: ClassTag](initialBehavior: Behavior[T], resume: Boolean) extends Behavior[T] {

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
    if (Behavior.isAlive(current)) this else ScalaDSL.Stopped
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
    if (Behavior.isAlive(current)) this else ScalaDSL.Stopped
  }
}

object Restarter {
  class Apply[Thr <: Throwable](c: ClassTag[Thr], resume: Boolean) {
    def wrap[T](b: Behavior[T]) = Restarter(Behavior.validateAsInitial(b), resume)(c)
  }

  def apply[Thr <: Throwable: ClassTag](resume: Boolean = false): Apply[Thr] = new Apply(implicitly, resume)
}
