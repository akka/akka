/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.osgi.aries.blueprint

import akka.actor.ActorSystem

/**
 * Just a simple POJO that can contain an actor system.
 * Used for testing dependency injection with Blueprint
 */
class ActorSystemAwareBean(val system: ActorSystem) {

}
