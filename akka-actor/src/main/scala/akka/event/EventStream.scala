/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.event

import akka.actor.{ ActorRef, Actor, Props, ActorSystemImpl, Terminated }
import akka.util.Subclassification

class EventStream(debug: Boolean = false) extends LoggingBus with SubchannelClassification {

  type Event = AnyRef
  type Classifier = Class[_]

  val subclassification = new Subclassification[Class[_]] {
    def isEqual(x: Class[_], y: Class[_]) = x == y
    def isSubclass(x: Class[_], y: Class[_]) = y isAssignableFrom x
  }

  @volatile
  private var reaper: ActorRef = _

  protected def classify(event: AnyRef): Class[_] = event.getClass

  protected def publish(event: AnyRef, subscriber: ActorRef) = subscriber ! event

  override def subscribe(subscriber: ActorRef, channel: Class[_]): Boolean = {
    if (debug) publish(Logging.Debug(this, "subscribing " + subscriber + " to channel " + channel))
    if (reaper ne null) reaper ! subscriber
    super.subscribe(subscriber, channel)
  }

  override def unsubscribe(subscriber: ActorRef, channel: Class[_]): Boolean = {
    if (debug) publish(Logging.Debug(this, "unsubscribing " + subscriber + " from channel " + channel))
    super.unsubscribe(subscriber, channel)
  }

  override def unsubscribe(subscriber: ActorRef) {
    if (debug) publish(Logging.Debug(this, "unsubscribing " + subscriber + " from all channels"))
    super.unsubscribe(subscriber)
  }

  def start(app: ActorSystemImpl) {
    reaper = app.systemActorOf(Props(new Actor {
      def receive = {
        case ref: ActorRef   ⇒ watch(ref)
        case Terminated(ref) ⇒ unsubscribe(ref)
      }
    }), "MainBusReaper")
    subscribers foreach (reaper ! _)
  }

}