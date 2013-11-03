/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import scala.collection.immutable

import akka.actor._

/**
 * Defines messages exchanged between processors, channels and a journal.
 */
private[persistence] object JournalProtocol {
  /**
   * Instructs a journal to mark the `persistent` message as deleted.
   * A persistent message marked as deleted is not replayed during recovery.
   *
   * @param persistent persistent message.
   */
  case class Delete(persistent: Persistent)

  /**
   * Instructs a journal to persist a sequence of messages.
   *
   * @param persistentBatch batch of messages to be persisted.
   * @param processor requesting processor.
   */
  case class WriteBatch(persistentBatch: immutable.Seq[PersistentImpl], processor: ActorRef)

  /**
   * Instructs a journal to persist a message.
   *
   * @param persistent message to be persisted.
   * @param processor requesting processor.
   */
  case class Write(persistent: PersistentImpl, processor: ActorRef)

  /**
   * Reply message to a processor that `persistent` message has been successfully journaled.
   *
   * @param persistent persistent message.
   */
  case class WriteSuccess(persistent: PersistentImpl)

  /**
   * Reply message to a processor that `persistent` message could not be journaled.
   *
   * @param persistent persistent message.
   * @param cause failure cause.
   */
  case class WriteFailure(persistent: PersistentImpl, cause: Throwable)

  /**
   * Instructs a journal to loop a `message` back to `processor`, without persisting the
   * message. Looping of messages through a journal is required to preserve message order
   * with persistent messages.
   *
   * @param message message to be looped through the journal.
   * @param processor requesting processor.
   */
  case class Loop(message: Any, processor: ActorRef)

  /**
   * Reply message to a processor that a `message` has been looped through the journal.
   *
   * @param message looped message.
   */
  case class LoopSuccess(message: Any)

  /**
   * Instructs a journal to replay messages to `processor`.
   *
   * @param fromSequenceNr sequence number where replay should start.
   * @param toSequenceNr sequence number where replay should end (inclusive).
   * @param processorId requesting processor id.
   * @param processor requesting processor.
   */
  case class Replay(fromSequenceNr: Long, toSequenceNr: Long, processorId: String, processor: ActorRef)

  /**
   * Reply message to a processor that `persistent` message has been replayed.
   *
   * @param persistent persistent message.
   */
  case class Replayed(persistent: PersistentImpl)

  /**
   * Reply message to a processor that all `persistent` messages have been replayed.
   *
   * @param maxSequenceNr the highest stored sequence number (for a processor).
   */
  case class ReplaySuccess(maxSequenceNr: Long)

  /**
   * Reply message to a processor that not all `persistent` messages could have been
   * replayed.
   */
  case class ReplayFailure(cause: Throwable)
}

