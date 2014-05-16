/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import org.reactivestreams.spi.Subscription

/**
 * INTERNAL API
 */
private[akka] case object SubscribePending
/**
 * INTERNAL API
 */
private[akka] case class RequestMore(subscription: ActorSubscription[_], demand: Int)
/**
 * INTERNAL API
 */
private[akka] case class Cancel(subscriptions: ActorSubscription[_])
/**
 * INTERNAL API
 */
private[akka] case class ExposedPublisher(publisher: ActorPublisher[Any])

