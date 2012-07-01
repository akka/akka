package akka.osgi.aries.blueprint

import akka.actor.ActorSystem

/**
 * Just a simple POJO that can contain an actor system.
 * Used for testing dependency injection with Blueprint
 */
class ActorSystemAwareBean(val system: ActorSystem) {

}
