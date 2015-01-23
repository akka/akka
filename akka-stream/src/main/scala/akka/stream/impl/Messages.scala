/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import language.existentials
import org.reactivestreams.Subscription
import akka.actor.DeadLetterSuppression

/**
 * INTERNAL API
 */
private[akka] case object SubscribePending extends DeadLetterSuppression

/**
 * INTERNAL API
 */
private[akka] final case class RequestMore(subscription: ActorSubscription[_], demand: Long)
  extends DeadLetterSuppression

/**
 * INTERNAL API
 */
private[akka] final case class Cancel(subscription: ActorSubscription[_])
  extends DeadLetterSuppression

/**
 * INTERNAL API
 */
private[akka] final case class ExposedPublisher(publisher: ActorPublisher[Any])
  extends DeadLetterSuppression

