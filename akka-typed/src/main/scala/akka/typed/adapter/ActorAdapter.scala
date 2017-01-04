/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed
package adapter

import akka.{ actor ⇒ a }

private[typed] class ActorAdapter[T](_initialBehavior: Behavior[T]) extends a.Actor {
  import Behavior._

  var behavior: Behavior[T] = _initialBehavior

  if (!isAlive(behavior)) context.stop(self)

  val ctx = new ActorContextAdapter[T](context)

  var failures: Map[a.ActorRef, Throwable] = Map.empty

  def receive = {
    case a.Terminated(ref) ⇒
      val msg =
        if (failures contains ref) {
          val ex = failures(ref)
          failures -= ref
          Terminated(ActorRefAdapter(ref))(ex)
        } else Terminated(ActorRefAdapter(ref))(null)
      next(behavior.management(ctx, msg), msg)
    case a.ReceiveTimeout ⇒
      next(behavior.message(ctx, ctx.receiveTimeoutMsg), ctx.receiveTimeoutMsg)
    case msg: T @unchecked ⇒
      next(behavior.message(ctx, msg), msg)
  }

  private def next(b: Behavior[T], msg: Any): Unit = {
    if (isUnhandled(b)) unhandled(msg)
    behavior = canonicalize(b, behavior)
    if (!isAlive(behavior)) context.stop(self)
  }

  override def unhandled(msg: Any): Unit = msg match {
    case Terminated(ref) ⇒ throw new a.DeathPactException(toUntyped(ref))
    case other           ⇒ super.unhandled(other)
  }

  override val supervisorStrategy = a.OneForOneStrategy() {
    case ex ⇒
      val ref = sender()
      if (context.asInstanceOf[a.ActorCell].isWatching(ref)) failures = failures.updated(ref, ex)
      a.SupervisorStrategy.Stop
  }

  override def preStart(): Unit =
    next(behavior.management(ctx, PreStart), PreStart)
  override def preRestart(reason: Throwable, message: Option[Any]): Unit =
    next(behavior.management(ctx, PreRestart), PreRestart)
  override def postRestart(reason: Throwable): Unit =
    next(behavior.management(ctx, PreStart), PreStart)
  override def postStop(): Unit =
    next(behavior.management(ctx, PostStop), PostStop)
}
