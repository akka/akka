/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed
package internal
package adapter

import akka.{ actor ⇒ a }
import scala.concurrent.duration._
import scala.concurrent.ExecutionContextExecutor
import akka.annotation.InternalApi

/**
 * INTERNAL API. Wrapping an [[akka.actor.ActorContext]] as an [[ActorContext]].
 */
@InternalApi private[typed] class ActorContextAdapter[T](val untyped: a.ActorContext) extends ActorContext[T] {

  import ActorRefAdapter.sendSystemMessage
  import ActorRefAdapter.toUntyped

  override def self = ActorRefAdapter(untyped.self)
  override val system = ActorSystemAdapter(untyped.system)
  override def mailboxCapacity = 1 << 29 // FIXME
  override def children = untyped.children.map(ActorRefAdapter(_))
  override def child(name: String) = untyped.child(name).map(ActorRefAdapter(_))
  override def spawnAnonymous[U](behavior: Behavior[U], deployment: DeploymentConfig = EmptyDeploymentConfig) =
    ActorContextAdapter.spawnAnonymous(untyped, behavior, deployment)
  override def spawn[U](behavior: Behavior[U], name: String, deployment: DeploymentConfig = EmptyDeploymentConfig) =
    ActorContextAdapter.spawn(untyped, behavior, name, deployment)
  override def stop[U](child: ActorRef[U]) =
    toUntyped(child) match {
      case f: akka.actor.FunctionRef ⇒
        val cell = untyped.asInstanceOf[akka.actor.ActorCell]
        cell.removeFunctionRef(f)
      case c ⇒
        untyped.child(child.path.name) match {
          case Some(`c`) ⇒
            untyped.stop(c)
            true
          case _ ⇒
            false // none of our business
        }
    }
  override def watch[U](other: ActorRef[U]) = { untyped.watch(toUntyped(other)) }
  override def unwatch[U](other: ActorRef[U]) = { untyped.unwatch(toUntyped(other)) }
  var receiveTimeoutMsg: T = null.asInstanceOf[T]
  override def setReceiveTimeout(d: FiniteDuration, msg: T) = {
    receiveTimeoutMsg = msg
    untyped.setReceiveTimeout(d)
  }
  override def cancelReceiveTimeout(): Unit = {
    receiveTimeoutMsg = null.asInstanceOf[T]
    untyped.setReceiveTimeout(Duration.Undefined)
  }
  override def executionContext: ExecutionContextExecutor = untyped.dispatcher
  override def schedule[U](delay: FiniteDuration, target: ActorRef[U], msg: U): a.Cancellable = {
    import untyped.dispatcher
    untyped.system.scheduler.scheduleOnce(delay, toUntyped(target), msg)
  }
  override def spawnAdapter[U](f: U ⇒ T, name: String = ""): ActorRef[U] = {
    val cell = untyped.asInstanceOf[akka.actor.ActorCell]
    val ref = cell.addFunctionRef((_, msg) ⇒ untyped.self ! f(msg.asInstanceOf[U]), name)
    ActorRefAdapter[U](ref)
  }

}

/**
 * INTERNAL API
 */
@InternalApi private[typed] object ActorContextAdapter {
  def toUntyped[U](ctx: ActorContext[_]): a.ActorContext =
    ctx match {
      case adapter: ActorContextAdapter[_] ⇒ adapter.untyped
      case _ ⇒
        throw new UnsupportedOperationException("only adapted untyped ActorContext permissible " +
          s"($ctx of class ${ctx.getClass.getName})")
    }

  def toUntyped[U](ctx: scaladsl.ActorContext[_]): a.ActorContext =
    ctx match {
      case c: ActorContext[_] ⇒ toUntyped(c)
      case _ ⇒
        throw new UnsupportedOperationException("unknown ActorContext type " +
          s"($ctx of class ${ctx.getClass.getName})")
    }

  def toUntyped[U](ctx: javadsl.ActorContext[_]): a.ActorContext =
    ctx match {
      case c: ActorContext[_] ⇒ toUntyped(c)
      case _ ⇒
        throw new UnsupportedOperationException("unknown ActorContext type " +
          s"($ctx of class ${ctx.getClass.getName})")
    }

  def spawnAnonymous[T](ctx: akka.actor.ActorContext, behavior: Behavior[T], deployment: DeploymentConfig): ActorRef[T] = {
    Behavior.validateAsInitial(behavior)
    ActorRefAdapter(ctx.actorOf(PropsAdapter(() ⇒ behavior, deployment)))
  }

  def spawn[T](ctx: akka.actor.ActorContext, behavior: Behavior[T], name: String, deployment: DeploymentConfig): ActorRef[T] = {
    Behavior.validateAsInitial(behavior)
    ActorRefAdapter(ctx.actorOf(PropsAdapter(() ⇒ behavior, deployment), name))
  }
}
