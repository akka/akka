/**
 * Copyright (C) 2012-2013 Eligotech BV.
 */

package akka.persistence.journal

import scala.util._

import akka.actor.Actor
import akka.pattern.{ pipe, PromiseActorRef }
import akka.persistence._
import akka.serialization.Serialization

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
      Try(write(persistent.copy(sender = Serialization.serializedActorPath(sdr), resolved = false, confirmTarget = null, confirmMessage = null))) match {
        case Success(_) ⇒ processor forward WriteSuccess(persistent)
        case Failure(e) ⇒ processor forward WriteFailure(persistent, e); throw e
      }
    }
    case Replay(fromSequenceNr, toSequenceNr, processorId, processor) ⇒ {
      replayAsync(processorId, fromSequenceNr, toSequenceNr) { p ⇒
        if (!p.deleted) processor.tell(Replayed(p), extension.system.provider.resolveActorRef(p.sender))
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
