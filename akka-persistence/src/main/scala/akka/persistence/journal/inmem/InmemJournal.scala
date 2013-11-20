/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.journal.inmem

import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

import akka.actor._
import akka.pattern.ask
import akka.persistence._
import akka.persistence.journal.AsyncWriteJournal
import akka.util._

/**
 * INTERNAL API.
 *
 * In-memory journal for testing purposes only.
 */
private[persistence] class InmemJournal extends AsyncWriteJournal {
  val store = context.actorOf(Props[InmemStore])

  implicit val timeout = Timeout(5 seconds)

  import InmemStore._

  def writeAsync(persistentBatch: immutable.Seq[PersistentRepr]): Future[Unit] =
    (store ? WriteBatch(persistentBatch)).mapTo[Unit]

  def deleteAsync(processorId: String, fromSequenceNr: Long, toSequenceNr: Long, permanent: Boolean): Future[Unit] =
    (store ? Delete(processorId, fromSequenceNr, toSequenceNr, permanent)).mapTo[Unit]

  def confirmAsync(processorId: String, sequenceNr: Long, channelId: String): Future[Unit] =
    (store ? Confirm(processorId, sequenceNr, channelId)).mapTo[Unit]

  def replayAsync(processorId: String, fromSequenceNr: Long, toSequenceNr: Long)(replayCallback: (PersistentRepr) ⇒ Unit): Future[Long] =
    (store ? Replay(processorId, fromSequenceNr, toSequenceNr, replayCallback)).mapTo[Long]
}

/**
 * INTERNAL API.
 */
private[persistence] trait InmemMessages {
  // processor id => persistent message
  var messages = Map.empty[String, Vector[PersistentRepr]]

  def add(p: PersistentRepr) = messages = messages + (messages.get(p.processorId) match {
    case Some(ms) ⇒ p.processorId -> (ms :+ p)
    case None     ⇒ p.processorId -> Vector(p)
  })

  def update(pid: String, snr: Long)(f: PersistentRepr ⇒ PersistentRepr) = messages = messages.get(pid) match {
    case Some(ms) ⇒ messages + (pid -> ms.map(sp ⇒ if (sp.sequenceNr == snr) f(sp) else sp))
    case None     ⇒ messages
  }

  def delete(pid: String, snr: Long) = messages = messages.get(pid) match {
    case Some(ms) ⇒ messages + (pid -> ms.filterNot(_.sequenceNr == snr))
    case None     ⇒ messages
  }

  def read(pid: String, fromSnr: Long, toSnr: Long): immutable.Seq[PersistentRepr] = messages.get(pid) match {
    case Some(ms) ⇒ ms.filter(m ⇒ m.sequenceNr >= fromSnr && m.sequenceNr <= toSnr)
    case None     ⇒ Nil
  }

  def maxSequenceNr(pid: String): Long = {
    val snro = for {
      ms ← messages.get(pid)
      m ← ms.lastOption
    } yield m.sequenceNr
    snro.getOrElse(0L)
  }
}

/**
 * INTERNAL API.
 */
private[persistence] class InmemStore extends Actor with InmemMessages {
  import InmemStore._

  def receive = {
    case WriteBatch(pb) ⇒
      pb.foreach(add)
      success()
    case Delete(pid, fsnr, tsnr, false) ⇒
      fsnr to tsnr foreach { snr ⇒ update(pid, snr)(_.update(deleted = true)) }
      success()
    case Delete(pid, fsnr, tsnr, true) ⇒
      fsnr to tsnr foreach { snr ⇒ delete(pid, snr) }
      success()
    case Confirm(pid, snr, cid) ⇒
      update(pid, snr)(p ⇒ p.update(confirms = cid +: p.confirms))
      success()
    case Replay(pid, fromSnr, toSnr, callback) ⇒
      read(pid, fromSnr, toSnr).foreach(callback)
      success(maxSequenceNr(pid))
  }

  private def success(reply: Any = ()) =
    sender ! reply
}

private[persistence] object InmemStore {
  case class WriteBatch(pb: Seq[PersistentRepr])
  case class Delete(processorId: String, fromSequenceNr: Long, toSequenceNr: Long, permanent: Boolean)
  case class Confirm(processorId: String, sequenceNr: Long, channelId: String)
  case class Replay(processorId: String, fromSequenceNr: Long, toSequenceNr: Long, replayCallback: (PersistentRepr) ⇒ Unit)
}
