/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import scala.collection.immutable

import akka.actor._

/**
 * INTERNAL API.
 *
 * Messages exchanged between persistent actors, views, channels and a journal.
 */
private[persistence] object JournalProtocol {
  /**
   * Request to delete messages identified by `messageIds`. If `permanent` is set to `false`,
   * the persistent messages are marked as deleted, otherwise they are permanently deleted.
   */
  case class DeleteMessages(messageIds: immutable.Seq[PersistentId], permanent: Boolean, requestor: Option[ActorRef] = None)

  /**
   * Reply message to a successful [[DeleteMessages]] request.
   */
  case class DeleteMessagesSuccess(messageIds: immutable.Seq[PersistentId])

  /**
   * Reply message to a failed [[DeleteMessages]] request.
   */
  case class DeleteMessagesFailure(cause: Throwable)

  /**
   * Request to delete all persistent messages with sequence numbers up to `toSequenceNr`
   * (inclusive). If `permanent` is set to `false`, the persistent messages are marked
   * as deleted in the journal, otherwise they are permanently deleted from the journal.
   */
  case class DeleteMessagesTo(persistenceId: String, toSequenceNr: Long, permanent: Boolean)

  /**
   * Request to write delivery confirmations.
   */
  case class WriteConfirmations(confirmations: immutable.Seq[PersistentConfirmation], requestor: ActorRef)

  /**
   * Reply message to a successful [[WriteConfirmations]] request.
   */
  case class WriteConfirmationsSuccess(confirmations: immutable.Seq[PersistentConfirmation])

  /**
   * Reply message to a failed [[WriteConfirmations]] request.
   */
  case class WriteConfirmationsFailure(cause: Throwable)

  /**
   * Request to write messages.
   *
   * @param messages messages to be written.
   * @param persistentActor write requestor.
   */
  case class WriteMessages(messages: immutable.Seq[Resequenceable], persistentActor: ActorRef, actorInstanceId: Int)

  /**
   * Reply message to a successful [[WriteMessages]] request. This reply is sent to the requestor
   * before all subsequent [[WriteMessageSuccess]] replies.
   */
  case object WriteMessagesSuccessful

  /**
   * Reply message to a failed [[WriteMessages]] request. This reply is sent to the requestor
   * before all subsequent [[WriteMessagFailure]] replies.
   *
   * @param cause failure cause.
   */
  case class WriteMessagesFailed(cause: Throwable)

  /**
   * Reply message to a successful [[WriteMessages]] request. For each contained [[PersistentRepr]] message
   * in the request, a separate reply is sent to the requestor.
   *
   * @param persistent successfully written message.
   */
  case class WriteMessageSuccess(persistent: PersistentRepr, actorInstanceId: Int)

  /**
   * Reply message to a failed [[WriteMessages]] request. For each contained [[PersistentRepr]] message
   * in the request, a separate reply is sent to the requestor.
   *
   * @param message message failed to be written.
   * @param cause failure cause.
   */
  case class WriteMessageFailure(message: PersistentRepr, cause: Throwable, actorInstanceId: Int)

  /**
   * Request to loop a `message` back to `persistent actor`, without persisting the message. Looping of messages
   * through a journal is required to preserve message order with persistent messages.
   *
   * @param message message to be looped through the journal.
   * @param persistentActor loop requestor.
   */
  case class LoopMessage(message: Any, persistentActor: ActorRef, actorInstanceId: Int)

  /**
   * Reply message to a [[LoopMessage]] request.
   *
   * @param message looped message.
   */
  case class LoopMessageSuccess(message: Any, actorInstanceId: Int)

  /**
   * Request to replay messages to `persistentActor`.
   *
   * @param fromSequenceNr sequence number where replay should start (inclusive).
   * @param toSequenceNr sequence number where replay should end (inclusive).
   * @param max maximum number of messages to be replayed.
   * @param persistenceId requesting persistent actor id.
   * @param persistentActor requesting persistent actor.
   * @param replayDeleted `true` if messages marked as deleted shall be replayed.
   */
  case class ReplayMessages(fromSequenceNr: Long, toSequenceNr: Long, max: Long, persistenceId: String, persistentActor: ActorRef, replayDeleted: Boolean = false)

  /**
   * Reply message to a [[ReplayMessages]] request. A separate reply is sent to the requestor for each
   * replayed message.
   *
   * @param persistent replayed message.
   */
  case class ReplayedMessage(persistent: PersistentRepr)

  /**
   * Reply message to a successful [[ReplayMessages]] request. This reply is sent to the requestor
   * after all [[ReplayedMessage]] have been sent (if any).
   */
  case object ReplayMessagesSuccess

  /**
   * Reply message to a failed [[ReplayMessages]] request. This reply is sent to the requestor
   * if a replay could not be successfully completed.
   */
  case class ReplayMessagesFailure(cause: Throwable)

  /**
   * Request to read the highest stored sequence number of a given persistent actor.
   *
   * @param fromSequenceNr optional hint where to start searching for the maximum sequence number.
   * @param persistenceId requesting persistent actor id.
   * @param persistentActor requesting persistent actor.
   */
  case class ReadHighestSequenceNr(fromSequenceNr: Long = 1L, persistenceId: String, persistentActor: ActorRef)

  /**
   * Reply message to a successful [[ReadHighestSequenceNr]] request.
   *
   * @param highestSequenceNr read highest sequence number.
   */
  case class ReadHighestSequenceNrSuccess(highestSequenceNr: Long)

  /**
   * Reply message to a failed [[ReadHighestSequenceNr]] request.
   *
   * @param cause failure cause.
   */
  case class ReadHighestSequenceNrFailure(cause: Throwable)
}

