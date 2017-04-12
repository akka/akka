/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed
package internal
package adapter

import akka.{ actor ⇒ a }
import akka.annotation.InternalApi

/**
 * INTERNAL API
 */
@InternalApi private[typed] class ActorAdapter[T](_initialBehavior: Behavior[T]) extends a.Actor {
  import Behavior._
  import ActorRefAdapter.toUntyped

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
      next(Behavior.interpretSignal(behavior, ctx, msg), msg)
    case a.ReceiveTimeout ⇒
      next(Behavior.interpretMessage(behavior, ctx, ctx.receiveTimeoutMsg), ctx.receiveTimeoutMsg)
    case msg: T @unchecked ⇒
      next(Behavior.interpretMessage(behavior, ctx, msg), msg)
  }

  private def next(b: Behavior[T], msg: Any): Unit = {
    if (isUnhandled(b)) unhandled(msg)
    behavior = canonicalize(b, behavior, ctx)
    if (!isAlive(behavior)) context.stop(self)
  }

  override def unhandled(msg: Any): Unit = msg match {
    case Terminated(ref) ⇒ throw a.DeathPactException(toUntyped(ref))
    case msg: Signal     ⇒ // that's ok
    case other           ⇒ super.unhandled(other)
  }

  override val supervisorStrategy = a.OneForOneStrategy() {
    case ex ⇒
      val ref = sender()
      if (context.asInstanceOf[a.ActorCell].isWatching(ref)) failures = failures.updated(ref, ex)
      a.SupervisorStrategy.Stop
  }

  override def preStart(): Unit =
    behavior = Behavior.preStart(behavior, ctx)
  override def preRestart(reason: Throwable, message: Option[Any]): Unit =
    next(Behavior.interpretSignal(behavior, ctx, PreRestart), PreRestart)
  override def postRestart(reason: Throwable): Unit =
    behavior = Behavior.preStart(behavior, ctx)
  override def postStop(): Unit = {
    next(Behavior.interpretSignal(behavior, ctx, PostStop), PostStop)
  }
}
