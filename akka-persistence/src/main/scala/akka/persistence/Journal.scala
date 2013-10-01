/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import akka.actor._

private[persistence] trait JournalFactory {
  /**
   * Creates a new journal actor.
   */
  def createJournal(implicit factory: ActorRefFactory): ActorRef
}

/**
 * Defines messages exchanged between processors, channels and a journal.
 */
private[persistence] object Journal {
  /**
   * Instructs a journal to mark the `persistent` message as deleted.
   * A persistent message marked as deleted is not replayed during recovery.
   *
   * @param persistent persistent message.
   */
  case class Delete(persistent: Persistent)

  /**
   * Instructs a journal to persist a message.
   *
   * @param persistent message to be persisted.
   * @param processor requesting processor.
   */
  case class Write(persistent: PersistentImpl, processor: ActorRef)

  /**
   * Reply message to a processor that `persistent` message has been journaled.
   *
   * @param persistent persistent message.
   */
  case class Written(persistent: PersistentImpl)

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
  case class Looped(message: Any)

  /**
   * ...
   */
  case class Replay(fromSequenceNr: Long, toSequenceNr: Long, processor: ActorRef, processorId: String)

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
  case class ReplayCompleted(maxSequenceNr: Long)
}

