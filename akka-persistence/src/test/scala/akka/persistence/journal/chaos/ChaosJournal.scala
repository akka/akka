/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.journal.chaos

import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.forkjoin.ThreadLocalRandom

import akka.persistence._
import akka.persistence.journal.SyncWriteJournal
import akka.persistence.journal.inmem.InmemMessages

class WriteFailedException(ps: Seq[PersistentRepr])
  extends TestException(s"write failed for payloads = [${ps.map(_.payload)}]")

class ReplayFailedException(ps: Seq[PersistentRepr])
  extends TestException(s"recovery failed after replaying payloads = [${ps.map(_.payload)}]")

class ReadHighestFailedException
  extends TestException(s"recovery failed when reading highest sequence number")

/**
 * Keep [[ChaosJournal]] state in an external singleton so that it survives journal restarts.
 * The journal itself uses a dedicated dispatcher, so there won't be any visibility issues.
 */
private object ChaosJournalMessages extends InmemMessages

class ChaosJournal extends SyncWriteJournal {
  import ChaosJournalMessages.{ delete ⇒ del, _ }

  val config = context.system.settings.config.getConfig("akka.persistence.journal.chaos")
  val writeFailureRate = config.getDouble("write-failure-rate")
  val deleteFailureRate = config.getDouble("delete-failure-rate")
  val replayFailureRate = config.getDouble("replay-failure-rate")
  val readHighestFailureRate = config.getDouble("read-highest-failure-rate")

  def random = ThreadLocalRandom.current

  def writeMessages(messages: immutable.Seq[PersistentRepr]): Unit =
    if (shouldFail(writeFailureRate)) throw new WriteFailedException(messages)
    else messages.foreach(add)

  def deleteMessagesTo(persistenceId: String, toSequenceNr: Long, permanent: Boolean): Unit = {
    (1L to toSequenceNr).foreach { snr ⇒
      if (permanent) update(persistenceId, snr)(_.update(deleted = true))
      else del(persistenceId, snr)
    }
  }

  def asyncReplayMessages(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long)(replayCallback: (PersistentRepr) ⇒ Unit): Future[Unit] =
    if (shouldFail(replayFailureRate)) {
      val rm = read(persistenceId, fromSequenceNr, toSequenceNr, max)
      val sm = rm.take(random.nextInt(rm.length + 1))
      sm.foreach(replayCallback)
      Future.failed(new ReplayFailedException(sm))
    } else {
      read(persistenceId, fromSequenceNr, toSequenceNr, max).foreach(replayCallback)
      Future.successful(())
    }

  def asyncReadHighestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long] =
    if (shouldFail(readHighestFailureRate)) Future.failed(new ReadHighestFailedException)
    else Future.successful(highestSequenceNr(persistenceId))

  def shouldFail(rate: Double): Boolean =
    random.nextDouble() < rate
}
