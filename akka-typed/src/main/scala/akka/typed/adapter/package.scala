/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed

package object adapter {

  import language.implicitConversions
  import akka.dispatch.sysmsg

  implicit class ActorSystemOps(val sys: akka.actor.ActorSystem) extends AnyVal {
    def spawnAnonymous[T](behavior: Behavior[T], deployment: DeploymentConfig = EmptyDeploymentConfig): ActorRef[T] =
      ActorRefAdapter(sys.actorOf(PropsAdapter(Behavior.validateAsInitial(behavior), deployment)))
    def spawn[T](behavior: Behavior[T], name: String, deployment: DeploymentConfig = EmptyDeploymentConfig): ActorRef[T] =
      ActorRefAdapter(sys.actorOf(PropsAdapter(Behavior.validateAsInitial(behavior), deployment), name))
  }

  implicit class ActorContextOps(val ctx: akka.actor.ActorContext) extends AnyVal {
    def spawnAnonymous[T](behavior: Behavior[T], deployment: DeploymentConfig = EmptyDeploymentConfig): ActorRef[T] =
      ActorRefAdapter(ctx.actorOf(PropsAdapter(Behavior.validateAsInitial(behavior), deployment)))
    def spawn[T](behavior: Behavior[T], name: String, deployment: DeploymentConfig = EmptyDeploymentConfig): ActorRef[T] =
      ActorRefAdapter(ctx.actorOf(PropsAdapter(Behavior.validateAsInitial(behavior), deployment), name))
  }

  implicit def actorRefAdapter(ref: akka.actor.ActorRef): ActorRef[Any] = ActorRefAdapter(ref)

  private[adapter] def toUntyped[U](ref: ActorRef[U]): akka.actor.InternalActorRef =
    ref match {
      case adapter: ActorRefAdapter[_] ⇒ adapter.untyped
      case _                           ⇒ throw new UnsupportedOperationException(s"only adapted untyped ActorRefs permissible ($ref of class ${ref.getClass})")
    }

  private[adapter] def sendSystemMessage(untyped: akka.actor.InternalActorRef, signal: internal.SystemMessage): Unit =
    signal match {
      case internal.Create()                           ⇒ throw new IllegalStateException("WAT? No, seriously.")
      case internal.Terminate()                        ⇒ untyped.stop()
      case internal.Watch(watchee, watcher)            ⇒ untyped.sendSystemMessage(sysmsg.Watch(toUntyped(watchee), toUntyped(watcher)))
      case internal.Unwatch(watchee, watcher)          ⇒ untyped.sendSystemMessage(sysmsg.Unwatch(toUntyped(watchee), toUntyped(watcher)))
      case internal.DeathWatchNotification(ref, cause) ⇒ untyped.sendSystemMessage(sysmsg.DeathWatchNotification(toUntyped(ref), true, false))
      case internal.NoMessage                          ⇒ // just to suppress the warning
    }

}
