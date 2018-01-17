/**
 * Copyright (C) 2009-2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor.typed.internal

import akka.actor.{ ActorPath, ActorRefProvider, ActorRefScope, InternalActorRef, ScalaActorRef, ActorRef ⇒ UntypedActorRef }
import akka.actor.typed.ActorRef
import akka.annotation.InternalApi
import akka.dispatch.sysmsg

@InternalApi final private[akka] class AdaptingActorRef[O, M](val actualRef: ActorRef[M], val transform: O ⇒ M) extends ActorRef[O] {

  def tell(msg: O): Unit = {
    // transform on sending side, but do not apply transformation
    // AdaptedMessage has special handling to transform before processing message on the receiving actor thread
    actualRef.asInstanceOf[ActorRef[AnyRef]] ! AdaptedMessage(msg, transform)
  }

  def narrow[U <: O]: ActorRef[U] = this.asInstanceOf[ActorRef[U]]

  def upcast[U >: O]: ActorRef[U] = this.asInstanceOf[ActorRef[U]]

  def path: ActorPath = actualRef.path

  def compareTo(o: ActorRef[_]): Int = actualRef.compareTo(o)

}

@InternalApi final private[akka] class UntypedAdaptingActorRef[O, M](actual: InternalActorRef, transform: O ⇒ M) extends InternalActorRef with ActorRefScope {
  def path: ActorPath = actual.path

  private[akka] def isTerminated = actual.isTerminated

  def !(message: Any)(implicit sender: UntypedActorRef): Unit = {
    // FIXME what to do if sent something non O??
    actual ! transform(message.asInstanceOf[O])
  }

  // I don't think these will ever be called on an untyped-adapted-adapted-ref, but not sure
  def start(): Unit = actual.start()
  def resume(causedByFailure: Throwable): Unit = actual.resume(causedByFailure)
  def suspend(): Unit = actual.suspend()
  def stop(): Unit = actual.stop()
  def restart(cause: Throwable): Unit = actual.restart(cause)
  def sendSystemMessage(message: sysmsg.SystemMessage): Unit = actual.sendSystemMessage(message)
  def provider: ActorRefProvider = actual.provider

  def getParent: InternalActorRef = actual.getParent
  def getChild(name: Iterator[String]): InternalActorRef = actual.getChild(name)
  def isLocal: Boolean = actual.isLocal
}

final private[akka] case class AdaptedMessage[O, M](payload: O, transform: O ⇒ M)
