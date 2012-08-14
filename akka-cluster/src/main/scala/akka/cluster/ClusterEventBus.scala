/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import akka.event.ActorEventBus
import akka.event.SubchannelClassification
import akka.actor.ActorRef
import akka.util.Subclassification

/**
 * Changes to the Cluster are published to this local event bus
 * as [[akka.cluster.ClusterEvent.ClusterDomainEvent]] subclasses.
 */
class ClusterEventBus extends ActorEventBus with SubchannelClassification {

  type Event = AnyRef
  type Classifier = Class[_]

  protected implicit val subclassification = new Subclassification[Class[_]] {
    def isEqual(x: Class[_], y: Class[_]) = x == y
    def isSubclass(x: Class[_], y: Class[_]) = y isAssignableFrom x
  }

  protected def classify(event: AnyRef): Class[_] = event.getClass

  protected def publish(event: AnyRef, subscriber: ActorRef) = {
    if (subscriber.isTerminated) unsubscribe(subscriber)
    else subscriber ! event
  }

}