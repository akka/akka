/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

/**
 * Everything that gets injected into the actor.
 * Just a wrapper on self for now.
 */
private[akka] class ActorContext(val self: LocalActorRef) {

}