/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl

import language.existentials
import akka.actor.{ NoSerializationVerificationNeeded, DeadLetterSuppression }

/**
 * INTERNAL API
 */
private[akka] case object SubscribePending extends DeadLetterSuppression with NoSerializationVerificationNeeded

/**
 * INTERNAL API
 */
private[akka] final case class RequestMore(subscription: ActorSubscription[_], demand: Long)
  extends DeadLetterSuppression with NoSerializationVerificationNeeded

/**
 * INTERNAL API
 */
private[akka] final case class Cancel(subscription: ActorSubscription[_])
  extends DeadLetterSuppression with NoSerializationVerificationNeeded

/**
 * INTERNAL API
 */
private[akka] final case class ExposedPublisher(publisher: ActorPublisher[Any])
  extends DeadLetterSuppression with NoSerializationVerificationNeeded

