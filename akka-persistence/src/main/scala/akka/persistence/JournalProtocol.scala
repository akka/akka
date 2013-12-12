/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import scala.collection.immutable

import akka.actor._

import akka.persistence.serialization.Message

/**
 * INTERNAL API.
 *
 * Defines messages exchanged between processors, channels and a journal.
 */
private[persistence] object JournalProtocol {
  /**
   * Instructs a journal to delete all persistent messages with sequence numbers in
   * the range from `fromSequenceNr` to `toSequenceNr` (both inclusive). If `permanent`
   * is set to `false`, the persistent messages are marked as deleted in the journal,
   * otherwise they are permanently deleted from the journal.
   */
  case class Delete(processorId: String, fromSequenceNr: Long, toSequenceNr: Long, permanent: Boolean)

  /**
   * Message sent after confirming the receipt of a [[ConfirmablePersistent]] message.
   *
   * @param processorId id of the processor that sent the message corresponding to
   *                    this confirmation to a channel.
   * @param messageSequenceNr sequence number of the sent message.
   * @param channelId id of the channel that delivered the message corresponding to
   *                  this confirmation.
   * @param wrapperSequenceNr sequence number of the message stored by a persistent
   *                          channel. This message contains the [[Deliver]] request
   *                          with the message identified by `processorId` and
   *                          `messageSequenceNumber`.
   * @param channelEndpoint actor reference that sent the the message corresponding to
   *                        this confirmation. This is a child actor of the sending
   *                        [[Channel]] or [[PersistentChannel]].
   */
  case class Confirm(processorId: String, messageSequenceNr: Long, channelId: String, wrapperSequenceNr: Long = 0L, channelEndpoint: ActorRef = null) extends Message

  /**
   * Instructs a journal to persist a sequence of messages.
   *
   * @param persistentBatch batch of messages to be persisted.
   * @param processor requesting processor.
   */
  case class WriteBatch(persistentBatch: immutable.Seq[PersistentRepr], processor: ActorRef)

  /**
   * Reply message to a processor if a batch write succeeded. This message is received before
   * all subsequent [[WriteSuccess]] messages.
   */
  case object WriteBatchSuccess

  /**
   * Reply message to a processor if a batch write failed. This message is received before
   * all subsequent [[WriteFailure]] messages.
   *
   * @param cause failure cause.
   */
  case class WriteBatchFailure(cause: Throwable)

  /**
   * Reply message to a processor that `persistent` message has been successfully journaled.
   *
   * @param persistent persistent message.
   */
  case class WriteSuccess(persistent: PersistentRepr)

  /**
   * Reply message to a processor that `persistent` message could not be journaled.
   *
   * @param persistent persistent message.
   * @param cause failure cause.
   */
  case class WriteFailure(persistent: PersistentRepr, cause: Throwable)

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
  case class Replayed(persistent: PersistentRepr)

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

