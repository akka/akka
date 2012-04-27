/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.event

import akka.actor.{ ActorRef, ActorSystem, simpleName }
import akka.util.Subclassification

object EventStream {
  //Why is this here and why isn't there a failing test if it is removed?
  implicit def fromActorSystem(system: ActorSystem) = system.eventStream
}

/**
 * An Akka EventStream is a pub-sub stream of events both system and user generated,
 * where subscribers are ActorRefs and the channels are Classes and Events are any java.lang.Object.
 * EventStreams employ SubchannelClassification, which means that if you listen to a Class,
 * you'll receive any message that is of that type or a subtype.
 *
 * The debug flag in the constructor toggles if operations on this EventStream should also be published
 * as Debug-Events
 */
class EventStream(private val debug: Boolean = false) extends LoggingBus with SubchannelClassification {

  type Event = AnyRef
  type Classifier = Class[_]

  protected implicit val subclassification = new Subclassification[Class[_]] {
    def isEqual(x: Class[_], y: Class[_]) = x == y
    def isSubclass(x: Class[_], y: Class[_]) = y isAssignableFrom x
  }

  protected def classify(event: AnyRef): Class[_] = event.getClass

  protected def publish(event: AnyRef, subscriber: ActorRef) = {
    if (subscriber.isTerminated()) unsubscribe(subscriber)
    else subscriber ! event
  }

  override def subscribe(subscriber: ActorRef, channel: Class[_]): Boolean = {
    if (debug) publish(Logging.Debug(simpleName(this), this.getClass, "subscribing " + subscriber + " to channel " + channel))
    super.subscribe(subscriber, channel)
  }

  override def unsubscribe(subscriber: ActorRef, channel: Class[_]): Boolean = {
    val ret = super.unsubscribe(subscriber, channel)
    if (debug) publish(Logging.Debug(simpleName(this), this.getClass, "unsubscribing " + subscriber + " from channel " + channel))
    ret
  }

  override def unsubscribe(subscriber: ActorRef) {
    super.unsubscribe(subscriber)
    if (debug) publish(Logging.Debug(simpleName(this), this.getClass, "unsubscribing " + subscriber + " from all channels"))
  }

}
