/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.event

import language.implicitConversions

import akka.actor.{ ActorRef, ActorSystem }
import akka.event.Logging.simpleName
import akka.util.Subclassification
import java.util.concurrent.atomic.AtomicReference

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

  /** Either the list of subscribed actors, or a ref to an [[akka.event.EventStreamUnsubscriber]] */
  private val initiallySubscribedOrUnsubscriber = new AtomicReference[Either[Set[ActorRef], ActorRef]](Left(Set.empty))

  protected implicit val subclassification = new Subclassification[Class[_]] {
    def isEqual(x: Class[_], y: Class[_]) = x == y
    def isSubclass(x: Class[_], y: Class[_]) = y isAssignableFrom x
  }

  protected def classify(event: AnyRef): Class[_] = event.getClass

  protected def publish(event: AnyRef, subscriber: ActorRef) = {
    if (subscriber.isTerminated) unsubscribe(subscriber)
    else subscriber ! event
  }

  override def subscribe(subscriber: ActorRef, channel: Class[_]): Boolean = {
    if (subscriber eq null) throw new IllegalArgumentException("subscriber is null")
    if (debug) publish(Logging.Debug(simpleName(this), this.getClass, "subscribing " + subscriber + " to channel " + channel))
    registerWithUnsubscriber(subscriber)
    super.subscribe(subscriber, channel)
  }

  override def unsubscribe(subscriber: ActorRef, channel: Class[_]): Boolean = {
    if (subscriber eq null) throw new IllegalArgumentException("subscriber is null")
    val ret = super.unsubscribe(subscriber, channel)
    unregisterIfNoMoreSubscribedChannels(ret, subscriber, channel)
    if (debug) publish(Logging.Debug(simpleName(this), this.getClass, "unsubscribing " + subscriber + " from channel " + channel))
    ret
  }

  override def unsubscribe(subscriber: ActorRef) {
    if (subscriber eq null) throw new IllegalArgumentException("subscriber is null")
    super.unsubscribe(subscriber)
    unregisterFromUnsubscriber(subscriber)
    if (debug) publish(Logging.Debug(simpleName(this), this.getClass, "unsubscribing " + subscriber + " from all channels"))
  }

  /**
   * INTERNAL API
   */
  private[akka] def initUnsubscriber(unsubscriber: ActorRef): Boolean = {
    initiallySubscribedOrUnsubscriber.get match {
      case value @ Left(subscribers) ⇒
        if (initiallySubscribedOrUnsubscriber.compareAndSet(value, Right(unsubscriber))) {
          if (debug) publish(Logging.Debug(simpleName(this), this.getClass, "initialized unsubscriber to: " + unsubscriber + ", registering " + subscribers.size + " initial subscribers with it"))
          subscribers foreach registerWithUnsubscriber
          true
        } else {
          // recurse, because either new subscribers have been registered since `get` (retry Left case),
          // or another thread has succeeded in setting it's unsubscriber (end on Right case)
          initUnsubscriber(unsubscriber)
        }

      case Right(presentUnsubscriber) ⇒
        if (debug) publish(Logging.Debug(simpleName(this), this.getClass, s"not using unsubscriber $unsubscriber, because already initialized with $presentUnsubscriber"))
        false
    }
  }

  /**
   * INTERNAL API
   */
  private[akka] def registerWithUnsubscriber(subscriber: ActorRef): Unit = {
    initiallySubscribedOrUnsubscriber.get match {
      case value @ Left(subscribers) ⇒
        if (!initiallySubscribedOrUnsubscriber.compareAndSet(value, Left(subscribers + subscriber)))
          registerWithUnsubscriber(subscriber)

      case Right(unsubscriber) ⇒
        unsubscriber ! EventStreamUnsubscriber.Register(subscriber)
    }
  }

  /**
   * INTERNAL API
   */
  private[akka] def unregisterFromUnsubscriber(subscriber: ActorRef): Unit = {
    initiallySubscribedOrUnsubscriber.get match {
      case value @ Left(subscribers) ⇒
        if (!initiallySubscribedOrUnsubscriber.compareAndSet(value, Left(subscribers - subscriber)))
          unregisterFromUnsubscriber(subscriber)

      case Right(unsubscriber) ⇒
        unsubscriber ! EventStreamUnsubscriber.Unregister(subscriber)
    }
  }

  /**
   * INTERNAL API
   */
  private[akka] def unregisterIfNoMoreSubscribedChannels(unsubscribeHadDiff: Boolean, subscriber: ActorRef, channel: Class[_]) {
    if (!unsubscribeHadDiff || !hasSubscriptions(subscriber)) {
      if (debug) publish(Logging.Debug(simpleName(this), this.getClass, "subscriber " + subscriber + " has now 0 channel subscriptions, after unsubscribing from channel" + channel))
      unregisterFromUnsubscriber(subscriber)
    }
  }

}
