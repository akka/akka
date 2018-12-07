/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed
package internal
package adapter

import akka.actor.ActorRefProvider
import akka.actor.InvalidMessageException
import akka.{ actor ⇒ untyped }
import akka.annotation.InternalApi
import akka.dispatch.sysmsg

/**
 * INTERNAL API
 */
@InternalApi private[typed] class ActorRefAdapter[-T](val untypedRef: untyped.InternalActorRef)
  extends ActorRef[T] with internal.ActorRefImpl[T] with internal.InternalRecipientRef[T] {

  override def path: untyped.ActorPath = untypedRef.path

  override def tell(msg: T): Unit = {
    if (msg == null) throw new InvalidMessageException("[null] is not an allowed message")
    untypedRef ! msg
  }

  // impl ActorRefImpl
  override def isLocal: Boolean = untypedRef.isLocal
  // impl ActorRefImpl
  override def sendSystem(signal: internal.SystemMessage): Unit =
    ActorRefAdapter.sendSystemMessage(untypedRef, signal)

  // impl InternalRecipientRef
  override def provider: ActorRefProvider = untypedRef.provider
  // impl InternalRecipientRef
  def isTerminated: Boolean = untypedRef.isTerminated

  @throws(classOf[java.io.ObjectStreamException])
  private def writeReplace(): AnyRef = SerializedActorRef[T](this)
}

private[akka] object ActorRefAdapter {
  def apply[T](ref: untyped.ActorRef): ActorRef[T] = new ActorRefAdapter(ref.asInstanceOf[untyped.InternalActorRef])

  def toUntyped[U](ref: ActorRef[U]): akka.actor.InternalActorRef =
    ref match {
      case adapter: ActorRefAdapter[_]   ⇒ adapter.untypedRef
      case system: ActorSystemAdapter[_] ⇒ system.untypedSystem.guardian
      case _ ⇒
        throw new UnsupportedOperationException("only adapted untyped ActorRefs permissible " +
          s"($ref of class ${ref.getClass.getName})")
    }

  def sendSystemMessage(untypedRef: akka.actor.InternalActorRef, signal: internal.SystemMessage): Unit =
    signal match {
      case internal.Create()    ⇒ throw new IllegalStateException("WAT? No, seriously.")
      case internal.Terminate() ⇒ untypedRef.stop()
      case internal.Watch(watchee, watcher) ⇒ untypedRef.sendSystemMessage(
        sysmsg.Watch(
          toUntyped(watchee),
          toUntyped(watcher)))
      case internal.Unwatch(watchee, watcher)      ⇒ untypedRef.sendSystemMessage(sysmsg.Unwatch(toUntyped(watchee), toUntyped(watcher)))
      case internal.DeathWatchNotification(ref, _) ⇒ untypedRef.sendSystemMessage(sysmsg.DeathWatchNotification(toUntyped(ref), true, false))
      case internal.NoMessage                      ⇒ // just to suppress the warning
    }
}
