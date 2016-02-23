/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.event

import akka.actor._
import akka.event.Logging.simpleName
import java.util.concurrent.atomic.AtomicInteger

/**
 * INTERNAL API
 *
 * Watches all actors which subscribe on the given eventStream, and unsubscribes them from it when they are Terminated.
 *
 * Assumptions note:
 * We do not guarantee happens-before in the EventStream when 2 threads subscribe(a) / unsubscribe(a) on the same actor,
 * thus the messages sent to this actor may appear to be reordered - this is fine, because the worst-case is starting to
 * needlessly watch the actor which will not cause trouble for the stream. This is a trade-off between slowing down
 * subscribe calls * because of the need of linearizing the history message sequence and the possibility of sometimes
 * watching a few actors too much - we opt for the 2nd choice here.
 */
private[akka] class EventStreamUnsubscriber(eventStream: EventStream, debug: Boolean = false) extends Actor {

  import EventStreamUnsubscriber._

  override def preStart() {
    if (debug) eventStream.publish(Logging.Debug(simpleName(getClass), getClass, s"registering unsubscriber with $eventStream"))
    eventStream initUnsubscriber self
  }

  def receive = {
    case Register(actor) ⇒
      if (debug) eventStream.publish(Logging.Debug(simpleName(getClass), getClass, s"watching $actor in order to unsubscribe from EventStream when it terminates"))
      context watch actor

    case UnregisterIfNoMoreSubscribedChannels(actor) if eventStream.hasSubscriptions(actor) ⇒
    // do nothing
    // hasSubscriptions can be slow, but it's better for this actor to take the hit than the EventStream

    case UnregisterIfNoMoreSubscribedChannels(actor) ⇒
      if (debug) eventStream.publish(Logging.Debug(simpleName(getClass), getClass, s"unwatching $actor, since has no subscriptions"))
      context unwatch actor

    case Terminated(actor) ⇒
      if (debug) eventStream.publish(Logging.Debug(simpleName(getClass), getClass, s"unsubscribe $actor from $eventStream, because it was terminated"))
      eventStream unsubscribe actor
  }
}

/**
 * INTERNAL API
 *
 * Provides factory for [[akka.event.EventStreamUnsubscriber]] actors with **unique names**.
 * This is needed if someone spins up more [[EventStream]]s using the same [[akka.actor.ActorSystem]],
 * each stream gets it's own unsubscriber.
 */
private[akka] object EventStreamUnsubscriber {

  private val unsubscribersCount = new AtomicInteger(0)

  final case class Register(actor: ActorRef)

  final case class UnregisterIfNoMoreSubscribedChannels(actor: ActorRef)

  private def props(eventStream: EventStream, debug: Boolean) =
    Props(classOf[EventStreamUnsubscriber], eventStream, debug)

  def start(system: ActorSystem, stream: EventStream) = {
    val debug = system.settings.config.getBoolean("akka.actor.debug.event-stream")
    system.asInstanceOf[ExtendedActorSystem]
      .systemActorOf(props(stream, debug), "eventStreamUnsubscriber-" + unsubscribersCount.incrementAndGet())
  }

}

/**
 * INTERNAL API
 *
 * Watches all actors which subscribe on the given event stream, and unsubscribes them from it when they are Terminated.
 */
private[akka] class ActorClassificationUnsubscriber(bus: ManagedActorClassification, debug: Boolean) extends Actor with Stash {

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
 * INTERNAL API
 *
 * Provides factory for [[akka.event.ActorClassificationUnsubscriber]] actors with **unique names**.
 */
private[akka] object ActorClassificationUnsubscriber {

  private val unsubscribersCount = new AtomicInteger(0)

  final case class Register(actor: ActorRef, seq: Int)
  final case class Unregister(actor: ActorRef, seq: Int)

  def start(system: ActorSystem, bus: ManagedActorClassification, debug: Boolean = false) = {
    val debug = system.settings.config.getBoolean("akka.actor.debug.event-stream")
    system.asInstanceOf[ExtendedActorSystem]
      .systemActorOf(props(bus, debug), "actorClassificationUnsubscriber-" + unsubscribersCount.incrementAndGet())
  }

  private def props(eventBus: ManagedActorClassification, debug: Boolean) = Props(classOf[ActorClassificationUnsubscriber], eventBus, debug)

}

