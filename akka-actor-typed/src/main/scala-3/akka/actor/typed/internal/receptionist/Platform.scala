/*
 * Copyright (C) 2009-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.receptionist

import akka.actor.typed.ActorRef

private[receptionist] object Platform {
  type Aux[P] = AbstractServiceKey { type Protocol = P }

  type Service[K <: Aux[_]] = K match {
    case Aux[t] => ActorRef[t]
  }

  type Subscriber[K <: Aux[_]] = K match {
    case Aux[t] => ActorRef[ReceptionistMessages.Listing[t]]
  }
}
