/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed
package adapter

import akka.{ actor ⇒ a }

private[typed] class ActorRefAdapter[-T](val untyped: a.InternalActorRef)
  extends ActorRef[T](untyped.path) with internal.ActorRefImpl[T] {

  override def tell(msg: T): Unit = untyped ! msg
  override def isLocal: Boolean = true
  override def sendSystem(signal: internal.SystemMessage): Unit = sendSystemMessage(untyped, signal)
}

private[typed] object ActorRefAdapter {
  def apply[T](untyped: a.ActorRef): ActorRef[T] = new ActorRefAdapter(untyped.asInstanceOf[a.InternalActorRef])
}
