/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.event

import akka.actor._
import akka.event.Logging.simpleName
import java.util.concurrent.atomic.AtomicInteger

/**
 * Watches all actors which subscribe on the given event stream, and unsubscribes them from it when they are Terminated.
 */
class EventStreamUnsubscriber(eventStream: EventStream, debug: Boolean) extends Actor {

  import EventStreamUnsubscriber._

  override def preStart() {
    super.preStart()
    if (debug) eventStream.publish(Logging.Debug(simpleName(getClass), getClass, s"registering unsubscriber with $eventStream"))
    eventStream initUnsubscriber self
  }

  def receive = {
    case Register(actor) ⇒
      if (debug) eventStream.publish(Logging.Debug(simpleName(getClass), getClass, s"watching $actor in order to unsubscribe from EventStream when it terminates"))
      context watch actor

    case Unregister(actor) ⇒
      if (debug) eventStream.publish(Logging.Debug(simpleName(getClass), getClass, s"unwatching $actor"))
      context unwatch actor

    case Terminated(actor) ⇒
      if (debug) eventStream.publish(Logging.Debug(simpleName(getClass), getClass, s"unsubscribe $actor from $eventStream, because it was terminated"))

      eventStream unsubscribe actor
  }
}

object EventStreamUnsubscriber {

  final case class Register(actor: ActorRef)
  final case class Unregister(actor: ActorRef)

  def props(eventStream: EventStream, debug: Boolean = false) =
    Props(classOf[EventStreamUnsubscriber], eventStream, debug)

}

/**
 * Watches all actors which subscribe on the given event stream, and unsubscribes them from it when they are Terminated.
 */
private[akka] class ActorClassificationUnsubscriber(bus: ActorClassification, debug: Boolean) extends Actor with Stash {

  import ActorClassificationUnsubscriber._

  private var atSeq = 0
  private def nextSeq = atSeq + 1

  override def preStart() {
    super.preStart()
    if (debug) context.system.eventStream.publish(Logging.Debug(simpleName(getClass), getClass, s"will monitor $bus"))
  }

  def receive = {
    case Register(actor, seq) if seq == nextSeq ⇒
      if (debug) context.system.eventStream.publish(Logging.Debug(simpleName(getClass), getClass, s"registered watch for $actor in $bus"))
      context watch actor
      atSeq = nextSeq
      unstashAll()

    case reg: Register ⇒
      stash()

    case Unregister(actor, seq) if seq == nextSeq ⇒
      if (debug) context.system.eventStream.publish(Logging.Debug(simpleName(getClass), getClass, s"unregistered watch of $actor in $bus"))
      context unwatch actor
      atSeq = nextSeq
      unstashAll()

    case unreg: Unregister ⇒
      stash()

    case Terminated(actor) ⇒
      if (debug) context.system.eventStream.publish(Logging.Debug(simpleName(getClass), getClass, s"actor $actor has terminated, unsubscribing it from $bus"))
      // the `unsubscribe` will trigger another `Unregister(actor, _)` message to this unsubscriber;
      // but since that actor is terminated, there cannot be any harm in processing an Unregister for it.
      bus unsubscribe actor
  }

}

/**
 * Extension providing factory for [[akka.event.ActorClassificationUnsubscriber]] actors with unique names.
 */
private[akka] object ActorClassificationUnsubscriber {

  private val unsubscribersCount = new AtomicInteger(0)

  final case class Register(actor: ActorRef, seq: Int)
  final case class Unregister(actor: ActorRef, seq: Int)

  def start(system: ActorSystem, bus: ActorClassification) = {
    val debug = system.settings.config.getBoolean("akka.actor.debug.event-stream")
    system.asInstanceOf[ExtendedActorSystem]
      .systemActorOf(props(bus, debug), "actorClassificationUnsubscriber-" + unsubscribersCount.incrementAndGet())
  }

  private def props(eventBus: ActorClassification, debug: Boolean) = Props(classOf[ActorClassificationUnsubscriber], eventBus, debug)

}

