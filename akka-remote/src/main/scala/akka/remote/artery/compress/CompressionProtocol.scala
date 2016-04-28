/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.compress

import akka.actor.ActorRef

object CompressionProtocol {

  // TODO do we bind into ActorRef or keep it "could be anything"?

  /** Sent by the "receiving" node after allocating a compression id to a given [[akka.actor.ActorRef]] */
  final case class CompressionAdvertisement(ref: ActorRef, id: Long)

  // technically revoking compression would also be interesting, but more advanced, not doing it for now
  // locally we'll do it anyway since "node went away, can clean up all compressions for it"
}
