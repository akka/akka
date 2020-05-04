/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.delivery

import scala.collection.immutable

import akka.actor.typed.ActorRef
import akka.actor.typed.delivery.internal.DeliverySerializable
import akka.annotation.ApiMayChange
import akka.annotation.InternalApi

/**
 * Actor message protocol for storing and confirming reliable delivery of messages. A [[akka.actor.typed.Behavior]]
 * implementation of this protocol can optionally be used with [[ProducerController]] when messages shall survive
 * a crash of the producer side.
 *
 * An implementation of this exists in `akka.persistence.typed.delivery.EventSourcedProducerQueue`.
 */
@ApiMayChange // TODO #28719 when removing ApiMayChange consider removing `case class` for some of the messages
object DurableProducerQueue {

  type SeqNr = Long
  // Timestamp in millis since epoch, System.currentTimeMillis
  type TimestampMillis = Long

  type ConfirmationQualifier = String

  val NoQualifier: ConfirmationQualifier = ""

  trait Command[A]

  /**
   * Request that is used at startup to retrieve the unconfirmed messages and current sequence number.
   */
  final case class LoadState[A](replyTo: ActorRef[State[A]]) extends Command[A]

  /**
   * Store the fact that a message is to be sent. Replies with [[StoreMessageSentAck]] when
   * the message has been successfully been stored.
   *
   * This command may be retied and the implementation should be idempotent, i.e. deduplicate
   * already processed sequence numbers.
   */
  final case class StoreMessageSent[A](sent: MessageSent[A], replyTo: ActorRef[StoreMessageSentAck]) extends Command[A]

  final case class StoreMessageSentAck(storedSeqNr: SeqNr)

  /**
   * Store the fact that a message has been confirmed to be delivered and processed.
   *
   * This command may be retied and the implementation should be idempotent, i.e. deduplicate
   * already processed sequence numbers.
   */
  final case class StoreMessageConfirmed[A](
      seqNr: SeqNr,
      confirmationQualifier: ConfirmationQualifier,
      timestampMillis: TimestampMillis)
      extends Command[A]

  object State {
    def empty[A]: State[A] = State(1L, 0L, Map.empty, Vector.empty)
  }
  final case class State[A](
      currentSeqNr: SeqNr,
      highestConfirmedSeqNr: SeqNr,
      confirmedSeqNr: Map[ConfirmationQualifier, (SeqNr, TimestampMillis)],
      unconfirmed: immutable.IndexedSeq[MessageSent[A]])
      extends DeliverySerializable

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] sealed trait Event extends DeliverySerializable

  /**
   * The fact (event) that a message has been sent.
   */
  final case class MessageSent[A](
      seqNr: SeqNr,
      message: A,
      ack: Boolean,
      confirmationQualifier: ConfirmationQualifier,
      timestampMillis: TimestampMillis)
      extends Event

  /**
   * INTERNAL API: The fact (event) that a message has been confirmed to be delivered and processed.
   */
  @InternalApi private[akka] final case class Confirmed(
      seqNr: SeqNr,
      confirmationQualifier: ConfirmationQualifier,
      timestampMillis: TimestampMillis)
      extends Event

  /**
   * INTERNAL API: Remove entries related to the confirmationQualifiers that haven't been used for a while.
   */
  @InternalApi private[akka] final case class Cleanup(confirmationQualifiers: Set[String]) extends Event

}
