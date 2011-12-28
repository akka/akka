/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.event

import akka.actor.{ ActorRef, Actor, Props, ActorSystemImpl, Terminated, ActorSystem, simpleName }
import akka.util.Subclassification
import java.util.concurrent.atomic.AtomicInteger

object EventStream {
  implicit def fromActorSystem(system: ActorSystem) = system.eventStream
  val generation = new AtomicInteger
}

class A(x: Int = 0) extends Exception("x=" + x)
class B extends A

class EventStream(val debug: Boolean = false) extends LoggingBus with SubchannelClassification {

  type Event = AnyRef
  type Classifier = Class[_]

  val subclassification = new Subclassification[Class[_]] {
    def isEqual(x: Class[_], y: Class[_]) = x == y
    def isSubclass(x: Class[_], y: Class[_]) = y isAssignableFrom x
  }

  protected def classify(event: AnyRef): Class[_] = event.getClass

  protected def publish(event: AnyRef, subscriber: ActorRef) = {
    if (subscriber.isTerminated) unsubscribe(subscriber)
    else subscriber ! event
  }

  override def subscribe(subscriber: ActorRef, channel: Class[_]): Boolean = {
    if (debug) publish(Logging.Debug(simpleName(this), "subscribing " + subscriber + " to channel " + channel))
    super.subscribe(subscriber, channel)
  }

  override def unsubscribe(subscriber: ActorRef, channel: Class[_]): Boolean = {
    val ret = super.unsubscribe(subscriber, channel)
    if (debug) publish(Logging.Debug(simpleName(this), "unsubscribing " + subscriber + " from channel " + channel))
    ret
  }

  override def unsubscribe(subscriber: ActorRef) {
    super.unsubscribe(subscriber)
    if (debug) publish(Logging.Debug(simpleName(this), "unsubscribing " + subscriber + " from all channels"))
  }

}