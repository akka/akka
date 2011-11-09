/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.event

import akka.actor.{ ActorRef, Actor, Props }
import akka.AkkaApplication
import akka.actor.Terminated

class MainBus(debug: Boolean = false) extends LoggingBus with LookupClassification {

  type Event = AnyRef
  type Classifier = Class[_]

  @volatile
  private var reaper: ActorRef = _

  protected def mapSize = 16

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

  def start(app: AkkaApplication) {
    reaper = app.systemActorOf(Props(new Actor {
      def receive = {
        case ref: ActorRef   ⇒ watch(ref)
        case Terminated(ref) ⇒ unsubscribe(ref)
      }
    }), "MainBusReaper")
    subscribers.values foreach (reaper ! _)
  }

  def printSubscribers: String = {
    val sb = new StringBuilder
    for (c ← subscribers.keys) sb.append(c + " -> " + subscribers.valueIterator(c).mkString("[", ", ", "]"))
    sb.toString
  }

}