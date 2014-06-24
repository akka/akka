/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.journal.japi

import scala.concurrent.Future

import akka.actor.Actor
import akka.japi.Procedure
import akka.persistence.journal.{ AsyncRecovery ⇒ SAsyncReplay }
import akka.persistence.PersistentRepr

/**
 * Java API: asynchronous message replay and sequence number recovery interface.
 */
abstract class AsyncRecovery extends SAsyncReplay with AsyncRecoveryPlugin { this: Actor ⇒
  import context.dispatcher

  final def asyncReplayMessages(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long)(replayCallback: (PersistentRepr) ⇒ Unit) =
    doAsyncReplayMessages(persistenceId, fromSequenceNr, toSequenceNr, max, new Procedure[PersistentRepr] {
      def apply(p: PersistentRepr) = replayCallback(p)
    }).map(Unit.unbox)

  final def asyncReadHighestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long] =
    doAsyncReadHighestSequenceNr(persistenceId, fromSequenceNr: Long).map(_.longValue)
}
