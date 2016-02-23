/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed

import akka.{ actor ⇒ a }
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.ExecutionContextExecutor
import akka.event.LoggingReceive
import akka.actor.DeathPactException

/**
 * INTERNAL API. Mapping the execution of a [[Behavior]] onto a good old untyped
 * [[akka.actor.Actor]].
 */
private[typed] class ActorAdapter[T](_initialBehavior: () ⇒ Behavior[T]) extends akka.actor.Actor {
  import Behavior._

  var behavior = _initialBehavior()
  val ctx = new ActorContextAdapter[T](context)

  def receive = LoggingReceive {
    case akka.actor.Terminated(ref) ⇒
      val msg = Terminated(ActorRef(ref))
      next(behavior.management(ctx, msg), msg)
    case akka.actor.ReceiveTimeout ⇒
      next(behavior.management(ctx, ReceiveTimeout), ReceiveTimeout)
    case msg ⇒
      val m = msg.asInstanceOf[T]
      next(behavior.message(ctx, m), m)
  }

  private def next(b: Behavior[T], msg: Any): Unit = {
    if (isUnhandled(b)) unhandled(msg)
    behavior = canonicalize(ctx, b, behavior)
    if (!isAlive(behavior)) {
      context.stop(self)
    }
  }

  override def unhandled(msg: Any): Unit = msg match {
    case Terminated(ref) ⇒ throw new DeathPactException(ref.untypedRef)
    case other           ⇒ super.unhandled(other)
  }

  override val supervisorStrategy = a.OneForOneStrategy() {
    case ex ⇒
      import Failed._
      import akka.actor.{ SupervisorStrategy ⇒ s }
      val f = Failed(ex, ActorRef(sender()))
      next(behavior.management(ctx, f), f)
      f.getDecision match {
        case Resume  ⇒ s.Resume
        case Restart ⇒ s.Restart
        case Stop    ⇒ s.Stop
        case _       ⇒ s.Escalate
      }
  }

  override def preStart(): Unit =
    next(behavior.management(ctx, PreStart), PreStart)
  override def preRestart(reason: Throwable, message: Option[Any]): Unit =
    next(behavior.management(ctx, PreRestart(reason)), PreRestart(reason))
  override def postRestart(reason: Throwable): Unit =
    next(behavior.management(ctx, PostRestart(reason)), PostRestart(reason))
  override def postStop(): Unit =
    next(behavior.management(ctx, PostStop), PostStop)
}

/**
 * INTERNAL API. Wrapping an [[akka.actor.ActorContext]] as an [[ActorContext]].
 */
private[typed] class ActorContextAdapter[T](ctx: akka.actor.ActorContext) extends ActorContext[T] {
  import Ops._
  def self = ActorRef(ctx.self)
  def props = Props(ctx.props)
  val system = ActorSystem(ctx.system)
  def children = ctx.children.map(ActorRef(_))
  def child(name: String) = ctx.child(name).map(ActorRef(_))
  def spawnAnonymous[U](props: Props[U]) = ctx.spawn(props)
  def spawn[U](props: Props[U], name: String) = ctx.spawn(props, name)
  def actorOf(props: a.Props) = ctx.actorOf(props)
  def actorOf(props: a.Props, name: String) = ctx.actorOf(props, name)
  def stop(child: ActorRef[Nothing]) =
    child.untypedRef match {
      case f: akka.actor.FunctionRef ⇒
        val cell = ctx.asInstanceOf[akka.actor.ActorCell]
        cell.removeFunctionRef(f)
      case _ ⇒
        ctx.child(child.path.name) match {
          case Some(ref) if ref == child.untypedRef ⇒
            ctx.stop(child.untypedRef)
            true
          case _ ⇒
            false // none of our business
        }
    }
  def watch[U](other: ActorRef[U]) = { ctx.watch(other.untypedRef); other }
  def watch(other: a.ActorRef) = { ctx.watch(other); other }
  def unwatch[U](other: ActorRef[U]) = { ctx.unwatch(other.untypedRef); other }
  def unwatch(other: a.ActorRef) = { ctx.unwatch(other); other }
  def setReceiveTimeout(d: Duration) = ctx.setReceiveTimeout(d)
  def executionContext: ExecutionContextExecutor = ctx.dispatcher
  def schedule[U](delay: FiniteDuration, target: ActorRef[U], msg: U): a.Cancellable = {
    import ctx.dispatcher
    ctx.system.scheduler.scheduleOnce(delay, target.untypedRef, msg)
  }
  def spawnAdapter[U](f: U ⇒ T) = {
    val cell = ctx.asInstanceOf[akka.actor.ActorCell]
    val ref = cell.addFunctionRef((_, msg) ⇒ ctx.self ! f(msg.asInstanceOf[U]))
    ActorRef[U](ref)
  }
}

/**
 * INTERNAL API. A small Actor that translates between message protocols.
 */
private[typed] class MessageWrapper(f: Any ⇒ Any) extends akka.actor.Actor {
  def receive = {
    case msg ⇒ context.parent ! f(msg)
  }
}
