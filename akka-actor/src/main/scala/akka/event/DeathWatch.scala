/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.event

import akka.actor._

/**
 * The contract of DeathWatch is not properly expressed using the type system
 * Whenever there is a publish, all listeners to the Terminated Actor should be atomically removed
 * A failed subscribe should also only mean that the Classifier (ActorRef) that is listened to is already shut down
 * See InVMMonitoring for semantics
 */
trait DeathWatch extends ActorEventBus with ActorClassifier {
  type Event = Terminated

  protected final def classify(event: Event): Classifier = event.actor
}

object InVMMonitoring extends DeathWatch with ActorClassification {

  def mapSize = 1024

  override def publish(event: Event): Unit = {
    val monitors = dissociate(classify(event))
    if (monitors.nonEmpty) monitors.foreach(_ ! event)
  }

  override def subscribe(subscriber: Subscriber, to: Classifier): Boolean = {
    if (!super.subscribe(subscriber, to)) {
      subscriber ! Terminated(subscriber, new ActorKilledException("Already terminated when linking"))
      false
    } else true
  }
}