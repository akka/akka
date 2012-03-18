package akka.camel.internal

import java.util.concurrent.ConcurrentHashMap
import org.apache.camel.processor.SendProcessor
import akka.actor.{ Props, ActorRef, Terminated, Actor }
import org.apache.camel.Endpoint
import akka.camel._
import akka.util.NonFatal

/**
 * Watches the end of life of <code>Producer</code>s.
 * Removes a <code>Producer</code> from the <code>ProducerRegistry</code> when it is <code>Terminated</code>,
 * which in turn stops the <code>SendProcessor</code>.
 */
private class ProducerWatcher(registry: ProducerRegistry) extends Actor {
  override def receive = {
    case RegisterProducer(actorRef) ⇒ context.watch(actorRef)
    case Terminated(actorRef)       ⇒ registry.unregisterProducer(actorRef)
  }
}

private case class RegisterProducer(actorRef: ActorRef)

/**
 * For internal use only.
 * Manages the Camel objects for <code>Producer</code>s.
 * Every <code>Producer</code> needs an <code>Endpoint</code> and a <code>SendProcessor</code>
 * to produce messages over an <code>Exchange</code>.
 */
private[camel] trait ProducerRegistry {
  this: Camel ⇒
  private val camelObjects = new ConcurrentHashMap[ActorRef, (Endpoint, SendProcessor)]()
  private val watcher = system.actorOf(Props(new ProducerWatcher(this)))

  private def registerWatch(actorRef: ActorRef) {
    watcher ! RegisterProducer(actorRef)
  }

  /**
   * For internal use only.
   * Unregisters <code>Endpoint</code> and <code>SendProcessor</code> and stops the SendProcessor
   */
  private[camel] def unregisterProducer(actorRef: ActorRef): Unit = {
    // Terminated cannot be sent before the actor is created in the processing of system messages.
    Option(camelObjects.remove(actorRef)).foreach {
      case (_, processor) ⇒
        try {
          processor.stop()
          system.eventStream.publish(EndpointDeActivated(actorRef))
        } catch {
          case NonFatal(e) ⇒ system.eventStream.publish(EndpointFailedToDeActivate(actorRef, e))
        }
    }
  }

  /**
   * For internal use only.
   * Creates <code>Endpoint</code> and <code>SendProcessor</code> and associates the actorRef to these.
   * @param actorRef the actorRef of the <code>Producer</code> actor.
   * @param endpointUri the endpoint Uri of the producer
   * @return <code>Endpoint</code> and <code>SendProcessor</code> registered for the actorRef
   */
  private[camel] def registerProducer(actorRef: ActorRef, endpointUri: String): (Endpoint, SendProcessor) = {
    try {
      val endpoint = context.getEndpoint(endpointUri)
      val processor = new SendProcessor(endpoint)

      camelObjects.putIfAbsent(actorRef, (endpoint, processor)) match {
        case null ⇒
          processor.start()
          registerWatch(actorRef)
          system.eventStream.publish(EndpointActivated(actorRef))
          (endpoint, processor)
        case prev ⇒ prev
      }
    } catch {
      case NonFatal(e) ⇒ {
        system.eventStream.publish(EndpointFailedToActivate(actorRef, e))
        // can't return null to the producer actor, so blow up actor in initialization.
        throw e
      }
    }
  }
}