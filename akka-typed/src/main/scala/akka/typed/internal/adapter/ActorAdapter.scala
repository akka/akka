/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed
package internal
package adapter

import akka.{ actor ⇒ a }
import akka.annotation.InternalApi
import akka.util.OptionVal

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
    if (Behavior.isUnhandled(b)) unhandled(msg)
    else {
      b match {
        case s: StoppedBehavior[T] ⇒
          // use StoppedBehavior with previous behavior or an explicitly given `postStop` behavior
          // until Terminate is received, i.e until postStop is invoked, and there PostStop
          // will be signaled to the previous/postStop behavior
          s.postStop match {
            case OptionVal.None ⇒
              // use previous as the postStop behavior
              behavior = new Behavior.StoppedBehavior(OptionVal.Some(behavior))
            case OptionVal.Some(postStop) ⇒
              // use the given postStop behavior, but canonicalize it
              behavior = new Behavior.StoppedBehavior(OptionVal.Some(Behavior.canonicalize(postStop, behavior, ctx)))
          }
          context.stop(self)
        case _ ⇒
          behavior = Behavior.canonicalize(b, behavior, ctx)
      }
    }
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

  override def preStart(): Unit = {
    behavior = validateAsInitial(undefer(behavior, ctx))
    if (!isAlive(behavior)) context.stop(self)
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    Behavior.interpretSignal(behavior, ctx, PreRestart)
    behavior = Behavior.stopped
  }

  override def postRestart(reason: Throwable): Unit = {
    behavior = validateAsInitial(undefer(behavior, ctx))
    if (!isAlive(behavior)) context.stop(self)
  }

  override def postStop(): Unit = {
    behavior match {
      case null                   ⇒ // skip PostStop
      case _: DeferredBehavior[_] ⇒
      // Do not undefer a DeferredBehavior as that may cause creation side-effects, which we do not want on termination.
      case s: StoppedBehavior[_] ⇒ s.postStop match {
        case OptionVal.Some(postStop) ⇒ Behavior.interpretSignal(postStop, ctx, PostStop)
        case OptionVal.None           ⇒ // no postStop behavior defined
      }
      case b ⇒ Behavior.interpretSignal(b, ctx, PostStop)
    }
    behavior = Behavior.stopped
  }
}
