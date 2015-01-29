/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.typed

import akka.{ actor ⇒ a }
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.ExecutionContextExecutor
import akka.event.LoggingReceive

/**
 * INTERNAL API. Mapping the execution of a [[Behavior]] onto a good old untyped
 * [[akka.actor.Actor]].
 */
private[typed] class ActorAdapter[T](_initialBehavior: () ⇒ Behavior[T]) extends akka.actor.Actor {
  import Behavior._

  var behavior = _initialBehavior()
  val ctx = new ActorContextAdapter[T](context)

  def receive = LoggingReceive {
    case akka.actor.Terminated(ref) ⇒ next(behavior.management(ctx, Terminated(ActorRef(ref))))
    case akka.actor.ReceiveTimeout  ⇒ next(behavior.management(ctx, ReceiveTimeout))
    case msg                        ⇒ next(behavior.message(ctx, msg.asInstanceOf[T]))
  }

  private def next(b: Behavior[T]): Unit = {
    behavior = canonicalize(ctx, b, behavior)
    if (!isAlive(behavior)) {
      context.stop(self)
    }
  }

  override val supervisorStrategy = a.OneForOneStrategy() {
    case ex ⇒
      import Failed._
      import akka.actor.{ SupervisorStrategy ⇒ s }
      val f = Failed(ex, ActorRef(sender()))
      next(behavior.management(ctx, f))
      f.getDecision match {
        case Resume  ⇒ s.Resume
        case Restart ⇒ s.Restart
        case Stop    ⇒ s.Stop
        case _       ⇒ s.Escalate
      }
  }

  override def preStart(): Unit =
    next(behavior.management(ctx, PreStart))
  override def preRestart(reason: Throwable, message: Option[Any]): Unit =
    next(behavior.management(ctx, PreRestart(reason)))
  override def postRestart(reason: Throwable): Unit =
    next(behavior.management(ctx, PostRestart(reason)))
  override def postStop(): Unit =
    next(behavior.management(ctx, PostStop))
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
  def stop(child: ActorRef[Nothing]) = ctx.child(child.path.name) match {
    case Some(ref) if ref == child.untypedRef ⇒
      ctx.stop(child.untypedRef)
      true
    case _ ⇒ false // none of our business
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
  def spawnAdapter[U](f: U ⇒ T) = ActorRef[U](ctx.actorOf(akka.actor.Props(classOf[MessageWrapper], f)))
}

/**
 * INTERNAL API. A small Actor that translates between message protocols.
 */
private[typed] class MessageWrapper(f: Any ⇒ Any) extends akka.actor.Actor {
  def receive = {
    case msg ⇒ context.parent ! f(msg)
  }
}
