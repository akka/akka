/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import org.reactivestreams.spi.Subscription

// FIXME INTERNAL API

case class OnSubscribe(subscription: Subscription)
// TODO performance improvement: skip wrapping ordinary elements in OnNext
case class OnNext(element: Any)
case object OnComplete
case class OnError(cause: Throwable)

case object SubscribePending

case class RequestMore(subscription: ActorSubscription[_], demand: Int)
case class Cancel(subscriptions: ActorSubscription[_])

case class ExposedPublisher(publisher: ActorPublisher[Any])

