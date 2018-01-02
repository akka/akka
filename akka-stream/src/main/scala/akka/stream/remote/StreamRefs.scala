/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.remote

import akka.actor.{ ActorRef, DeadLetterSuppression }
import akka.annotation.InternalApi
import akka.stream.impl.ReactiveStreamsCompliance

/** INTERNAL API: Protocol messages used by the various stream -ref implementations. */
@InternalApi
private[akka] object StreamRefs {

  @InternalApi
  sealed trait Protocol

  /**
   * Sequenced `Subscriber#onNext` equivalent.
   * The receiving end of these messages MUST fail the stream if it observes gaps in the sequence,
   * as these messages will not be re-delivered.
   *
   * Sequence numbers start from `0`.
   */
  @InternalApi
  final case class SequencedOnNext[T](seqNr: Long, payload: T) extends StreamRefs.Protocol {
    if (payload == null) throw ReactiveStreamsCompliance.elementMustNotBeNullException
  }

  /** Sent to a the receiver side of a SinkRef, once the sending side of the SinkRef gets signalled a Failure. */
  @InternalApi
  final case class RemoteSinkFailure(msg: String) extends StreamRefs.Protocol

  /** Sent to a the receiver side of a SinkRef, once the sending side of the SinkRef gets signalled a completion. */
  @InternalApi
  final case class RemoteSinkCompleted(seqNr: Long) extends StreamRefs.Protocol

  /**
   * Cumulative demand, equivalent to sequence numbering all events in a stream. *
   * This message may be re-delivered.
   */
  @InternalApi
  final case class CumulativeDemand(seqNr: Long) extends StreamRefs.Protocol with DeadLetterSuppression {
    if (seqNr <= 0) throw ReactiveStreamsCompliance.numberOfElementsInRequestMustBePositiveException
  }

  // --- exceptions ---

  final case class RemoteStreamRefActorTerminatedException(msg: String) extends RuntimeException(msg)
  final case class RemoteStreamRefFailedException(msg: String) extends RuntimeException(msg)
  final case class InvalidSequenceNumberException(expectedSeqNr: Long, gotSeqNr: Long, msg: String)
    extends IllegalStateException(s"$msg (expected: $expectedSeqNr, got: $gotSeqNr)")

  /**
   * Stream refs establish a connection between a local and remote actor, representing the origin and remote sides
   * of a stream. Each such actor refers to the other side as its "partner". We make sure that no other actor than
   * the initial partner can send demand/messages to the other side accidentally.
   *
   * This exception is thrown when a message is recived from a non-partner actor,
   * which could mean a bug or some actively malicient behaviour from the other side.
   *
   * This is not meant as a security feature, but rather as plain sanity-check.
   */
  final case class InvalidPartnerActorException(expectedRef: ActorRef, gotRef: ActorRef, msg: String)
    extends IllegalStateException(s"$msg (expected: $expectedRef, got: $gotRef)")

}

