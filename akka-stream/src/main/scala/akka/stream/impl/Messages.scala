/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import org.reactivestreams.Subscription

/**
 * INTERNAL API
 */
private[akka] case object SubscribePending

/**
 * INTERNAL API
 */
private[akka] case class RequestMore(subscription: LazySubscription[Any], demand: Int)

/**
 * INTERNAL API
 */
private[akka] case class Cancel(subscriptions: LazySubscription[Any])

/**
 * INTERNAL API
 */
private[akka] case class ExposedPublisher(publisher: LazyPublisherLike[Any])
