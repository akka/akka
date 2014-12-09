/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import language.existentials
import org.reactivestreams.Subscription

/**
 * INTERNAL API
 */
private[akka] case object SubscribePending
/**
 * INTERNAL API
 */
private[akka] case class RequestMore(subscription: ActorSubscription[_], demand: Long)
/**
 * INTERNAL API
 */
private[akka] case class Cancel(subscription: ActorSubscription[_])
/**
 * INTERNAL API
 */
private[akka] case class ExposedPublisher(publisher: ActorPublisher[Any])

