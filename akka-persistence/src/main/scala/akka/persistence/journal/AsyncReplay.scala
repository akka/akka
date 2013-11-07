/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.journal

import scala.concurrent.Future

import akka.persistence.PersistentRepr

/**
 * Asynchronous message replay interface.
 */
trait AsyncReplay {
  //#journal-plugin-api
  /**
   * Plugin API: asynchronously replays persistent messages. Implementations replay
   * a message by calling `replayCallback`. The returned future must be completed
   * when all messages (matching the sequence number bounds) have been replayed. The
   * future `Long` value must be the highest stored sequence number in the journal
   * for the specified processor. The future must be completed with a failure if any
   * of the persistent messages could not be replayed.
   *
   * The `replayCallback` must also be called with messages that have been marked
   * as deleted. In this case a replayed message's `deleted` method must return
   * `true`.
   *
   * The channel ids of delivery confirmations that are available for a replayed
   * message must be contained in that message's `confirms` sequence.
   *
   * @param processorId processor id.
   * @param fromSequenceNr sequence number where replay should start.
   * @param toSequenceNr sequence number where replay should end (inclusive).
   * @param replayCallback called to replay a single message. Can be called from any
   *                       thread.
   *
   * @see [[AsyncWriteJournal]]
   * @see [[SyncWriteJournal]]
   */
  def replayAsync(processorId: String, fromSequenceNr: Long, toSequenceNr: Long)(replayCallback: PersistentRepr ⇒ Unit): Future[Long]
  //#journal-plugin-api
}
