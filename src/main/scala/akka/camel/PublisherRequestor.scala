package akka.camel

import akka.actor._

/**
 * @author Martin Krasser
 */
private[camel] abstract class PublishRequestor extends Actor {
  private val events = collection.mutable.Set[ConsumerEvent]()
  private var publisher: Option[ActorRef] = None

  def receiveActorRegistryEvent: Receive

  def receive = {
    case InitPublishRequestor(pub) => {
      publisher = Some(pub)
      deliverBufferedEvents
    }
    case e: ActorRegistryEvent => receiveActorRegistryEvent(e)
    case _                     => { /* ignore */ }
  }

  protected def deliverCurrentEvent(event: ConsumerEvent) {
    publisher match {
      case Some(pub) => pub ! event
      case None      => events += event
    }
  }

  private def deliverBufferedEvents {
    for (event <- events) deliverCurrentEvent(event)
    events.clear
  }
}

/**
 * @author Martin Krasser
 */
private[camel] object PublishRequestor {
  def pastActorRegisteredEvents = for (actor <- Actor.registry.actors) yield ActorRegistered(actor)
}

/**
 * Command message to initialize a PublishRequestor to use <code>consumerPublisher</code>
 * for publishing consumer actors.
 */
private[camel] case class InitPublishRequestor(consumerPublisher: ActorRef)
