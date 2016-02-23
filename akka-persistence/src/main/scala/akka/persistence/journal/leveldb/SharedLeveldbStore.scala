/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.persistence.journal.leveldb

import akka.persistence.journal.AsyncWriteTarget
import akka.pattern.pipe
import scala.util.Try
import scala.util.Success
import scala.util.Failure
import scala.util.control.NonFatal
import akka.persistence.AtomicWrite
import scala.concurrent.Future

/**
 * A LevelDB store that can be shared by multiple actor systems. The shared store must be
 * set for each actor system that uses the store via `SharedLeveldbJournal.setStore`. The
 * shared LevelDB store is for testing only.
 */
class SharedLeveldbStore extends { val configPath = "akka.persistence.journal.leveldb-shared.store" } with LeveldbStore {
  import AsyncWriteTarget._
  import context.dispatcher

  def receive = {
    case WriteMessages(messages) ⇒
      // TODO it would be nice to DRY this with AsyncWriteJournal, but this is using
      //      AsyncWriteProxy message protocol
      val atomicWriteCount = messages.count(_.isInstanceOf[AtomicWrite])
      val prepared = Try(preparePersistentBatch(messages))
      val writeResult = (prepared match {
        case Success(prep) ⇒
          // in case the asyncWriteMessages throws
          try asyncWriteMessages(prep) catch { case NonFatal(e) ⇒ Future.failed(e) }
        case f @ Failure(_) ⇒
          // exception from preparePersistentBatch => rejected
          Future.successful(messages.collect { case a: AtomicWrite ⇒ f })
      }).map { results ⇒
        if (results.nonEmpty && results.size != atomicWriteCount)
          throw new IllegalStateException("asyncWriteMessages returned invalid number of results. " +
            s"Expected [${prepared.get.size}], but got [${results.size}]")
        results
      }

      writeResult.pipeTo(sender())

    case DeleteMessagesTo(pid, tsnr) ⇒
      asyncDeleteMessagesTo(pid, tsnr).pipeTo(sender())

    case ReplayMessages(persistenceId, fromSequenceNr, toSequenceNr, max) ⇒
      // TODO it would be nice to DRY this with AsyncWriteJournal, but this is using
      //      AsyncWriteProxy message protocol
      val replyTo = sender()
      val readHighestSequenceNrFrom = math.max(0L, fromSequenceNr - 1)
      asyncReadHighestSequenceNr(persistenceId, readHighestSequenceNrFrom).flatMap { highSeqNr ⇒
        if (highSeqNr == 0L || max == 0L)
          Future.successful(highSeqNr)
        else {
          val toSeqNr = math.min(toSequenceNr, highSeqNr)
          asyncReplayMessages(persistenceId, fromSequenceNr, toSeqNr, max) { p ⇒
            if (!p.deleted) // old records from 2.3 may still have the deleted flag
              adaptFromJournal(p).foreach(replyTo ! _)
          }.map(_ ⇒ highSeqNr)
        }
      }.map {
        highSeqNr ⇒ ReplaySuccess(highSeqNr)
      }.recover {
        case e ⇒ ReplayFailure(e)
      }.pipeTo(replyTo)
  }
}
