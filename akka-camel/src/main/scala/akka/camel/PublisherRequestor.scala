package akka.camel

import akka.actor._

/**
 * Base class for concrete (un)publish requestors. Subclasses are responsible for requesting
 * (un)publication of consumer actors by calling <code>deliverCurrentEvent</code>.
 *
 * @author Martin Krasser
 */
private[camel] abstract class PublishRequestor extends Actor {
  private val events = collection.mutable.Set[ConsumerEvent]()
  private var publisher: Option[ActorRef] = None

  def receiveActorRegistryEvent: Receive

  /**
   * Accepts
   * <ul>
   * <li><code>InitPublishRequestor</code> messages to configure a publisher for this requestor.</li>
   * <li><code>ActorRegistryEvent</code> messages to be handled <code>receiveActorRegistryEvent</code>
   * implementators</li>.
   * </ul>
   * Other messages are simply ignored. Calls to <code>deliverCurrentEvent</code> prior to setting a
   * publisher are buffered. They will be sent after a publisher has been set.
   */
  def receive = {
    case InitPublishRequestor(pub) ⇒ {
      publisher = Some(pub)
      deliverBufferedEvents
    }
    case e: ActorRegistryEvent ⇒ receiveActorRegistryEvent(e)
    case _                     ⇒ { /* ignore */ }
  }

  /**
   * Deliver the given <code>event</code> to <code>publisher</code> or buffer the event if
   * <code>publisher</code> is not defined yet.
   */
  protected def deliverCurrentEvent(event: ConsumerEvent) {
    publisher match {
      case Some(pub) ⇒ pub ! event
      case None      ⇒ events += event
    }
  }

  private def deliverBufferedEvents {
    for (event ← events) deliverCurrentEvent(event)
    events.clear
  }
}

/**
 * @author Martin Krasser
 */
private[camel] object PublishRequestor {
  def pastActorRegisteredEvents = for (actor ← Actor.registry.local.actors) yield ActorRegistered(actor.address, actor)
}

/**
 * Command message to initialize a PublishRequestor to use <code>publisher</code>
 * for publishing consumer actors.
 */
private[camel] case class InitPublishRequestor(publisher: ActorRef)
