/**
 * Copyright (C) 2012-2013 Eligotech BV.
 */

package akka.persistence.journal

import scala.collection.immutable
import scala.concurrent.Future
import scala.util._

import akka.actor._
import akka.pattern.{ pipe, PromiseActorRef }
import akka.persistence._
import akka.persistence.JournalProtocol._

/**
 * Abstract journal, optimized for asynchronous, non-blocking writes.
 */
trait AsyncWriteJournal extends Actor with AsyncReplay {
  import AsyncWriteJournal._
  import context.dispatcher

  private val resequencer = context.actorOf(Props[Resequencer])
  private var resequencerCounter = 1L

  final def receive = {
    case Write(persistent, processor) ⇒ {
      val csdr = sender
      val cctr = resequencerCounter
      val psdr = if (sender.isInstanceOf[PromiseActorRef]) context.system.deadLetters else sender
      writeAsync(persistent.prepareWrite(psdr)) map {
        _ ⇒ Desequenced(WriteSuccess(persistent), cctr, processor, csdr)
      } recover {
        case e ⇒ Desequenced(WriteFailure(persistent, e), cctr, processor, csdr)
      } pipeTo (resequencer)
      resequencerCounter += 1
    }
    case WriteBatch(persistentBatch, processor) ⇒ {
      val csdr = sender
      val cctr = resequencerCounter
      val psdr = if (sender.isInstanceOf[PromiseActorRef]) context.system.deadLetters else sender
      def resequence(f: PersistentImpl ⇒ Any) = persistentBatch.zipWithIndex.foreach {
        case (p, i) ⇒ resequencer ! Desequenced(f(p), cctr + i, processor, csdr)
      }
      writeBatchAsync(persistentBatch.map(_.prepareWrite(psdr))) onComplete {
        case Success(_) ⇒ resequence(WriteSuccess(_))
        case Failure(e) ⇒ resequence(WriteFailure(_, e))
      }
      resequencerCounter += persistentBatch.length
    }
    case Replay(fromSequenceNr, toSequenceNr, processorId, processor) ⇒ {
      // Send replayed messages and replay result to processor directly. No need
      // to resequence replayed messages relative to written and looped messages.
      replayAsync(processorId, fromSequenceNr, toSequenceNr) { p ⇒
        if (!p.deleted) processor.tell(Replayed(p), p.sender)
      } map {
        maxSnr ⇒ ReplaySuccess(maxSnr)
      } recover {
        case e ⇒ ReplayFailure(e)
      } pipeTo (processor)
    }
    case c @ Confirm(processorId, sequenceNr, channelId) ⇒ {
      confirmAsync(processorId, sequenceNr, channelId) onComplete {
        case Success(_) ⇒ context.system.eventStream.publish(c)
        case Failure(e) ⇒ // TODO: publish failure to event stream
      }
      context.system.eventStream.publish(c)
    }
    case Delete(persistent: PersistentImpl) ⇒ {
      deleteAsync(persistent) onComplete {
        case Success(_) ⇒ // TODO: publish success to event stream
        case Failure(e) ⇒ // TODO: publish failure to event stream
      }
    }
    case Loop(message, processor) ⇒ {
      resequencer ! Desequenced(LoopSuccess(message), resequencerCounter, processor, sender)
      resequencerCounter += 1
    }
  }

  //#journal-plugin-api
  /**
   * Plugin API.
   *
   * Asynchronously writes a `persistent` message to the journal.
   */
  def writeAsync(persistent: PersistentImpl): Future[Unit]

  /**
   * Plugin API.
   *
   * Asynchronously writes a batch of persistent messages to the journal. The batch write
   * must be atomic i.e. either all persistent messages in the batch are written or none.
   */
  def writeBatchAsync(persistentBatch: immutable.Seq[PersistentImpl]): Future[Unit]

  /**
   * Plugin API.
   *
   * Asynchronously marks a `persistent` message as deleted.
   */
  def deleteAsync(persistent: PersistentImpl): Future[Unit]

  /**
   * Plugin API.
   *
   * Asynchronously writes a delivery confirmation to the journal.
   */
  def confirmAsync(processorId: String, sequenceNr: Long, channelId: String): Future[Unit]
  //#journal-plugin-api
}

private[persistence] object AsyncWriteJournal {
  case class Desequenced(msg: Any, snr: Long, target: ActorRef, sender: ActorRef)

  class Resequencer extends Actor {
    import scala.collection.mutable.Map

    private val delayed = Map.empty[Long, Desequenced]
    private var delivered = 0L

    def receive = {
      case d: Desequenced ⇒ resequence(d)
    }

    @scala.annotation.tailrec
    private def resequence(d: Desequenced) {
      if (d.snr == delivered + 1) {
        delivered = d.snr
        d.target tell (d.msg, d.sender)
      } else {
        delayed += (d.snr -> d)
      }
      val ro = delayed.remove(delivered + 1)
      if (ro.isDefined) resequence(ro.get)
    }
  }
}

