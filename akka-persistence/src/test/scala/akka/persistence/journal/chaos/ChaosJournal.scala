/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.journal.chaos

import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.concurrent.forkjoin.ThreadLocalRandom

import akka.persistence._
import akka.persistence.journal.SyncWriteJournal
import akka.persistence.journal.inmem.InmemMessages

class WriteFailedException(ps: Seq[PersistentRepr])
  extends TestException(s"write failed for payloads = [${ps.map(_.payload)}]")

class ReplayFailedException(ps: Seq[PersistentRepr])
  extends TestException(s"replay failed after payloads = [${ps.map(_.payload)}]")

class DeleteFailedException(processorId: String, fromSequenceNr: Long, toSequenceNr: Long)
  extends TestException(s"delete failed for processor id = [${processorId}], from sequence number = [${fromSequenceNr}], to sequence number = [${toSequenceNr}]")

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

  def random = ThreadLocalRandom.current

  def write(persistentBatch: Seq[PersistentRepr]): Unit =
    if (shouldFail(writeFailureRate)) throw new WriteFailedException(persistentBatch)
    else persistentBatch.foreach(add)

  def delete(processorId: String, fromSequenceNr: Long, toSequenceNr: Long, permanent: Boolean): Unit =
    if (shouldFail(deleteFailureRate)) throw new DeleteFailedException(processorId, fromSequenceNr, toSequenceNr)
    else fromSequenceNr to toSequenceNr foreach { snr ⇒ if (permanent) del(processorId, snr) else update(processorId, snr)(_.update(deleted = true)) }

  def confirm(processorId: String, sequenceNr: Long, channelId: String): Unit =
    update(processorId, sequenceNr)(p ⇒ p.update(confirms = channelId +: p.confirms))

  def replayAsync(processorId: String, fromSequenceNr: Long, toSequenceNr: Long)(replayCallback: (PersistentRepr) ⇒ Unit): Future[Long] =
    if (shouldFail(replayFailureRate)) {
      val rm = read(processorId, fromSequenceNr, toSequenceNr)
      val sm = rm.take(random.nextInt(rm.length + 1))
      sm.foreach(replayCallback)
      Future.failed(new ReplayFailedException(sm))
    } else {
      read(processorId, fromSequenceNr, toSequenceNr).foreach(replayCallback)
      Future.successful(maxSequenceNr(processorId))
    }

  def shouldFail(rate: Double): Boolean =
    random.nextDouble() < rate
}
