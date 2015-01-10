/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import scala.collection.immutable

import akka.actor._

/**
 * INTERNAL API.
 *
 * Messages exchanged between persistent actors, views and a journal.
 */
private[persistence] object JournalProtocol {

  /** Marker trait shared by internal journal messages. */
  sealed trait Message extends Protocol.Message
  /** Internal journal command. */
  sealed trait Request extends Message
  /** Internal journal acknowledgement. */
  sealed trait Response extends Message

  /**
   * Reply message to a failed [[DeleteMessages]] request.
   */
  final case class DeleteMessagesFailure(cause: Throwable)
    extends Response

  /**
   * Request to delete all persistent messages with sequence numbers up to `toSequenceNr`
   * (inclusive). If `permanent` is set to `false`, the persistent messages are marked
   * as deleted in the journal, otherwise they are permanently deleted from the journal.
   */
  final case class DeleteMessagesTo(persistenceId: String, toSequenceNr: Long, permanent: Boolean)
    extends Request

  /**
   * Request to write messages.
   *
   * @param messages messages to be written.
   * @param persistentActor write requestor.
   */
  final case class WriteMessages(messages: immutable.Seq[PersistentEnvelope], persistentActor: ActorRef, actorInstanceId: Int)
    extends Request

  /**
   * Reply message to a successful [[WriteMessages]] request. This reply is sent to the requestor
   * before all subsequent [[WriteMessageSuccess]] replies.
   */
  case object WriteMessagesSuccessful
    extends Response

  /**
   * Reply message to a failed [[WriteMessages]] request. This reply is sent to the requestor
   * before all subsequent [[WriteMessagFailure]] replies.
   *
   * @param cause failure cause.
   */
  final case class WriteMessagesFailed(cause: Throwable)
    extends Response

  /**
   * Reply message to a successful [[WriteMessages]] request. For each contained [[PersistentRepr]] message
   * in the request, a separate reply is sent to the requestor.
   *
   * @param persistent successfully written message.
   */
  final case class WriteMessageSuccess(persistent: PersistentRepr, actorInstanceId: Int)
    extends Response

  /**
   * Reply message to a failed [[WriteMessages]] request. For each contained [[PersistentRepr]] message
   * in the request, a separate reply is sent to the requestor.
   *
   * @param message message failed to be written.
   * @param cause failure cause.
   */
  final case class WriteMessageFailure(message: PersistentRepr, cause: Throwable, actorInstanceId: Int)
    extends Response

  /**
   * Reply message to a [[WriteMessages]] with a non-persistent message.
   *
   * @param message looped message.
   */
  final case class LoopMessageSuccess(message: Any, actorInstanceId: Int)
    extends Response

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
  final case class ReplayMessages(fromSequenceNr: Long, toSequenceNr: Long, max: Long, persistenceId: String, persistentActor: ActorRef, replayDeleted: Boolean = false)
    extends Request

  /**
   * Reply message to a [[ReplayMessages]] request. A separate reply is sent to the requestor for each
   * replayed message.
   *
   * @param persistent replayed message.
   */
  final case class ReplayedMessage(persistent: PersistentRepr)
    extends Response

  /**
   * Reply message to a successful [[ReplayMessages]] request. This reply is sent to the requestor
   * after all [[ReplayedMessage]] have been sent (if any).
   */
  case object ReplayMessagesSuccess
    extends Response

  /**
   * Reply message to a failed [[ReplayMessages]] request. This reply is sent to the requestor
   * if a replay could not be successfully completed.
   */
  final case class ReplayMessagesFailure(cause: Throwable)
    extends Response

  /**
   * Request to read the highest stored sequence number of a given persistent actor.
   *
   * @param fromSequenceNr optional hint where to start searching for the maximum sequence number.
   * @param persistenceId requesting persistent actor id.
   * @param persistentActor requesting persistent actor.
   */
  final case class ReadHighestSequenceNr(fromSequenceNr: Long = 1L, persistenceId: String, persistentActor: ActorRef)
    extends Request

  /**
   * Reply message to a successful [[ReadHighestSequenceNr]] request.
   *
   * @param highestSequenceNr read highest sequence number.
   */
  final case class ReadHighestSequenceNrSuccess(highestSequenceNr: Long)
    extends Response

  /**
   * Reply message to a failed [[ReadHighestSequenceNr]] request.
   *
   * @param cause failure cause.
   */
  final case class ReadHighestSequenceNrFailure(cause: Throwable)
    extends Response
}
