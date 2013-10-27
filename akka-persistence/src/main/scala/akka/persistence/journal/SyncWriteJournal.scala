/**
 * Copyright (C) 2012-2013 Eligotech BV.
 */

package akka.persistence.journal

import scala.collection.immutable
import scala.util._

import akka.actor.Actor
import akka.pattern.{ pipe, PromiseActorRef }
import akka.persistence._

/**
 * Abstract journal, optimized for synchronous writes.
 */
trait SyncWriteJournal extends Actor with AsyncReplay {
  import JournalProtocol._
  import context.dispatcher

  private val extension = Persistence(context.system)

  final def receive = {
    case Write(persistent, processor) ⇒ {
      val sdr = if (sender.isInstanceOf[PromiseActorRef]) context.system.deadLetters else sender
      Try(write(persistent.prepareWrite(sdr))) match {
        case Success(_) ⇒ processor forward WriteSuccess(persistent)
        case Failure(e) ⇒ processor forward WriteFailure(persistent, e); throw e
      }
    }
    case WriteBatch(persistentBatch, processor) ⇒ {
      val sdr = if (sender.isInstanceOf[PromiseActorRef]) context.system.deadLetters else sender
      Try(writeBatch(persistentBatch.map(_.prepareWrite(sdr)))) match {
        case Success(_) ⇒ persistentBatch.foreach(processor forward WriteSuccess(_))
        case Failure(e) ⇒ persistentBatch.foreach(processor forward WriteFailure(_, e)); throw e
      }
    }
    case Replay(fromSequenceNr, toSequenceNr, processorId, processor) ⇒ {
      replayAsync(processorId, fromSequenceNr, toSequenceNr) { p ⇒
        if (!p.deleted) processor.tell(Replayed(p), p.sender)
      } map {
        maxSnr ⇒ ReplaySuccess(maxSnr)
      } recover {
        case e ⇒ ReplayFailure(e)
      } pipeTo (processor)
    }
    case c @ Confirm(processorId, sequenceNr, channelId) ⇒ {
      confirm(processorId, sequenceNr, channelId)
      context.system.eventStream.publish(c) // TODO: turn off by default and allow to turn on by configuration
    }
    case Delete(persistent: PersistentImpl) ⇒ {
      delete(persistent)
    }
    case Loop(message, processor) ⇒ {
      processor forward LoopSuccess(message)
    }
  }

  //#journal-plugin-api
  /**
   * Plugin API.
   *
   * Synchronously writes a `persistent` message to the journal.
   */
  def write(persistent: PersistentImpl): Unit

  /**
   * Plugin API.
   *
   * Synchronously writes a batch of persistent messages to the journal. The batch write
   * must be atomic i.e. either all persistent messages in the batch are written or none.
   */
  def writeBatch(persistentBatch: immutable.Seq[PersistentImpl]): Unit

  /**
   * Plugin API.
   *
   * Synchronously marks a `persistent` message as deleted.
   */
  def delete(persistent: PersistentImpl): Unit

  /**
   * Plugin API.
   *
   * Synchronously writes a delivery confirmation to the journal.
   */
  def confirm(processorId: String, sequenceNr: Long, channelId: String): Unit
  //#journal-plugin-api
}
