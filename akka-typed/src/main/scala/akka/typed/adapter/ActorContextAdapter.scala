/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed
package adapter

import akka.{ actor ⇒ a }
import scala.concurrent.duration._
import scala.concurrent.ExecutionContextExecutor

/**
 * INTERNAL API. Wrapping an [[akka.actor.ActorContext]] as an [[ActorContext]].
 */
private[typed] class ActorContextAdapter[T](ctx: a.ActorContext) extends ActorContext[T] {

  override def self = ActorRefAdapter(ctx.self)
  override val system = ActorSystemAdapter(ctx.system)
  override def mailboxCapacity = 1 << 29 // FIXME
  override def children = ctx.children.map(ActorRefAdapter(_))
  override def child(name: String) = ctx.child(name).map(ActorRefAdapter(_))
  override def spawnAnonymous[U](behavior: Behavior[U], deployment: DeploymentConfig = EmptyDeploymentConfig) =
    ctx.spawnAnonymous(behavior, deployment)
  override def spawn[U](behavior: Behavior[U], name: String, deployment: DeploymentConfig = EmptyDeploymentConfig) =
    ctx.spawn(behavior, name, deployment)
  override def stop(child: ActorRef[Nothing]) =
    toUntyped(child) match {
      case f: akka.actor.FunctionRef ⇒
        val cell = ctx.asInstanceOf[akka.actor.ActorCell]
        cell.removeFunctionRef(f)
      case untyped ⇒
        ctx.child(child.path.name) match {
          case Some(`untyped`) ⇒
            ctx.stop(untyped)
            true
          case _ ⇒
            false // none of our business
        }
    }
  override def watch[U](other: ActorRef[U]) = { ctx.watch(toUntyped(other)); other }
  override def unwatch[U](other: ActorRef[U]) = { ctx.unwatch(toUntyped(other)); other }
  var receiveTimeoutMsg: T = null.asInstanceOf[T]
  override def setReceiveTimeout(d: FiniteDuration, msg: T) = {
    receiveTimeoutMsg = msg
    ctx.setReceiveTimeout(d)
  }
  override def cancelReceiveTimeout(): Unit = {
    receiveTimeoutMsg = null.asInstanceOf[T]
    ctx.setReceiveTimeout(Duration.Undefined)
  }
  override def executionContext: ExecutionContextExecutor = ctx.dispatcher
  override def schedule[U](delay: FiniteDuration, target: ActorRef[U], msg: U): a.Cancellable = {
    import ctx.dispatcher
    ctx.system.scheduler.scheduleOnce(delay, toUntyped(target), msg)
  }
  override def spawnAdapter[U](f: U ⇒ T): ActorRef[U] = {
    val cell = ctx.asInstanceOf[akka.actor.ActorCell]
    val ref = cell.addFunctionRef((_, msg) ⇒ ctx.self ! f(msg.asInstanceOf[U]))
    ActorRefAdapter[U](ref)
  }

}
