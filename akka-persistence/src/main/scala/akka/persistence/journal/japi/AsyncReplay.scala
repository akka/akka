/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.journal.japi

import java.lang.{ Long ⇒ JLong }

import scala.concurrent.Future

import akka.actor.Actor
import akka.japi.Procedure
import akka.persistence.journal.{ AsyncReplay ⇒ SAsyncReplay }
import akka.persistence.PersistentImpl

abstract class AsyncReplay extends SAsyncReplay { this: Actor ⇒
  import context.dispatcher

  final def replayAsync(processorId: String, fromSequenceNr: Long, toSequenceNr: Long)(replayCallback: (PersistentImpl) ⇒ Unit) =
    doReplayAsync(processorId, fromSequenceNr, toSequenceNr, new Procedure[PersistentImpl] {
      def apply(p: PersistentImpl) = replayCallback(p)
    }).map(_.longValue)

  /**
   * Plugin Java API.
   *
   * Asynchronously replays persistent messages. Implementations replay a message
   * by calling `replayCallback`. The returned future must be completed when all
   * messages (matching the sequence number bounds) have been replayed. The future
   * `Long` value must be the highest stored sequence number in the journal for the
   * specified processor. The future must be completed with a failure if any of
   * the persistent messages could not be replayed.
   *
   * The `replayCallback` must also be called with messages that have been marked
   * as deleted. In this case a replayed message's `deleted` field must be set to
   * `true`.
   *
   * The channel ids of delivery confirmations that are available for a replayed
   * message must be contained in that message's `confirms` sequence.
   *
   * @param processorId processor id.
   * @param fromSequenceNr sequence number where replay should start.
   * @param toSequenceNr sequence number where replay should end (inclusive).
   * @param replayCallback called to replay a single message.
   */
  def doReplayAsync(processorId: String, fromSequenceNr: Long, toSequenceNr: Long, replayCallback: Procedure[PersistentImpl]): Future[JLong]
}
