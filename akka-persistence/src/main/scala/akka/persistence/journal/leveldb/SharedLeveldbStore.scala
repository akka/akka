/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
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
      val prepared = Try(preparePersistentBatch(messages))
      val writeResult = (prepared match {
        case Success(prep) ⇒
          // in case the asyncWriteMessages throws
          try asyncWriteMessages(prep) catch { case NonFatal(e) ⇒ Future.failed(e) }
        case f @ Failure(_) ⇒
          // exception from preparePersistentBatch => rejected
          Future.successful(messages.collect { case a: AtomicWrite ⇒ f })
      }).map { results ⇒
        if (results.size != prepared.get.size)
          throw new IllegalStateException("asyncWriteMessages returned invalid number of results. " +
            s"Expected [${prepared.get.size}], but got [${results.size}]")
        results
      }

      writeResult.pipeTo(sender())

    case DeleteMessagesTo(pid, tsnr) ⇒
      asyncDeleteMessagesTo(pid, tsnr).pipeTo(sender())

    case ReadHighestSequenceNr(pid, fromSequenceNr) ⇒
      asyncReadHighestSequenceNr(pid, fromSequenceNr).pipeTo(sender())

    case ReplayMessages(pid, fromSnr, toSnr, max) ⇒
      Try(replayMessages(numericId(pid), fromSnr, toSnr, max)(p ⇒ adaptFromJournal(p).foreach { sender() ! _ })) match {
        case Success(max)   ⇒ sender() ! ReplaySuccess
        case Failure(cause) ⇒ sender() ! ReplayFailure(cause)
      }
  }
}
