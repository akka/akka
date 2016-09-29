/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com/>
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
final case class Restarter[T, Thr <: Throwable: ClassTag](behavior: () ⇒ Behavior[T], resume: Boolean) extends Behavior[T] {

  private[this] var current = behavior()

  // FIXME remove allocation overhead once finalized
  private def canonicalize(ctx: ActorContext[T], block: ⇒ Behavior[T]): Behavior[T] = {
    val b =
      try block
      catch {
        case ex: Thr ⇒
          ctx.system.eventStream.publish(Logging.Error(ex, ctx.self.toString, current.getClass, ex.getMessage))
          if (resume) current else restart(ctx)
      }
    current = Behavior.canonicalize(b, current)
    if (Behavior.isAlive(current)) this else ScalaDSL.Stopped
  }

  private def restart(ctx: ActorContext[T]): Behavior[T] = {
    try current.management(ctx, PreRestart) catch { case NonFatal(_) ⇒ }
    current = behavior()
    current.management(ctx, PostRestart)
  }

  override def management(ctx: ActorContext[T], signal: Signal): Behavior[T] =
    canonicalize(ctx, current.management(ctx, signal))

  override def message(ctx: ActorContext[T], msg: T): Behavior[T] =
    canonicalize(ctx, current.message(ctx, msg))
}

object Restarter {
  class Apply[Thr <: Throwable](c: ClassTag[Thr], resume: Boolean) {
    def wrap[T](p: Props[T]) = Props(() ⇒ Restarter(p.creator, resume)(c), p.dispatcher, p.mailboxCapacity)
  }

  def apply[Thr <: Throwable: ClassTag](resume: Boolean = false): Apply[Thr] = new Apply(implicitly, resume)
}
