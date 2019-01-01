/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed
package internal
package adapter

import akka.actor.ActorRefProvider
import akka.actor.InvalidMessageException
import akka.{ actor ⇒ a }
import akka.annotation.InternalApi
import akka.dispatch.sysmsg

/**
 * INTERNAL API
 */
@InternalApi private[typed] class ActorRefAdapter[-T](val untyped: a.InternalActorRef)
  extends ActorRef[T] with internal.ActorRefImpl[T] with internal.InternalRecipientRef[T] {

  override def path: a.ActorPath = untyped.path

  override def tell(msg: T): Unit = {
    if (msg == null) throw new InvalidMessageException("[null] is not an allowed message")
    untyped ! msg
  }

  // impl ActorRefImpl
  override def isLocal: Boolean = untyped.isLocal
  // impl ActorRefImpl
  override def sendSystem(signal: internal.SystemMessage): Unit =
    ActorRefAdapter.sendSystemMessage(untyped, signal)

  // impl InternalRecipientRef
  override def provider: ActorRefProvider = untyped.provider
  // impl InternalRecipientRef
  def isTerminated: Boolean = untyped.isTerminated

  @throws(classOf[java.io.ObjectStreamException])
  private def writeReplace(): AnyRef = SerializedActorRef[T](this)
}

private[akka] object ActorRefAdapter {
  def apply[T](untyped: a.ActorRef): ActorRef[T] = new ActorRefAdapter(untyped.asInstanceOf[a.InternalActorRef])

  def toUntyped[U](ref: ActorRef[U]): akka.actor.InternalActorRef =
    ref match {
      case adapter: ActorRefAdapter[_]   ⇒ adapter.untyped
      case system: ActorSystemAdapter[_] ⇒ system.untyped.guardian
      case _ ⇒
        throw new UnsupportedOperationException("only adapted untyped ActorRefs permissible " +
          s"($ref of class ${ref.getClass.getName})")
    }

  def sendSystemMessage(untyped: akka.actor.InternalActorRef, signal: internal.SystemMessage): Unit =
    signal match {
      case internal.Create()    ⇒ throw new IllegalStateException("WAT? No, seriously.")
      case internal.Terminate() ⇒ untyped.stop()
      case internal.Watch(watchee, watcher) ⇒ untyped.sendSystemMessage(
        sysmsg.Watch(
          toUntyped(watchee),
          toUntyped(watcher)))
      case internal.Unwatch(watchee, watcher)      ⇒ untyped.sendSystemMessage(sysmsg.Unwatch(toUntyped(watchee), toUntyped(watcher)))
      case internal.DeathWatchNotification(ref, _) ⇒ untyped.sendSystemMessage(sysmsg.DeathWatchNotification(toUntyped(ref), true, false))
      case internal.NoMessage                      ⇒ // just to suppress the warning
    }
}
