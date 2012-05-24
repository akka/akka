/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.event

import akka.actor._

/**
 * The contract of DeathWatch is not properly expressed using the type system
 * Whenever there is a publish, all listeners to the Terminated Actor should be atomically removed
 * A failed subscribe should also only mean that the Classifier (ActorRef) that is listened to is already shut down
 * See LocalDeathWatch for semantics
 */
abstract class DeathWatch extends ActorEventBus with ActorClassifier {
  type Event = Terminated

  protected final def classify(event: Event): Classifier = event.actor
}
