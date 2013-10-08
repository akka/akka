/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.journal.inmem

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

  def writeAsync(persistent: PersistentImpl): Future[Unit] =
    (store ? Write(persistent)).mapTo[Unit]

  def deleteAsync(persistent: PersistentImpl): Future[Unit] =
    (store ? Delete(persistent)).mapTo[Unit]

  def confirmAsync(processorId: String, sequenceNr: Long, channelId: String): Future[Unit] =
    (store ? Confirm(processorId, sequenceNr, channelId)).mapTo[Unit]

  def replayAsync(processorId: String, fromSequenceNr: Long, toSequenceNr: Long)(replayCallback: (PersistentImpl) ⇒ Unit): Future[Long] =
    (store ? Replay(processorId, fromSequenceNr, toSequenceNr, replayCallback)).mapTo[Long]
}

private[persistence] class InmemStore extends Actor {
  import InmemStore._

  // processor id => persistent message
  var messages = Map.empty[String, Vector[PersistentImpl]]

  def receive = {
    case Write(p)               ⇒ add(p); success()
    case Delete(p)              ⇒ update(p.processorId, p.sequenceNr)(_.copy(deleted = true)); success()
    case Confirm(pid, snr, cid) ⇒ update(pid, snr)(p ⇒ p.copy(confirms = cid +: p.confirms)); success()
    case Replay(pid, fromSnr, toSnr, callback) ⇒ {
      for {
        ms ← messages.get(pid)
        m ← ms
        if m.sequenceNr >= fromSnr && m.sequenceNr <= toSnr
      } callback(m)

      success(maxSequenceNr(pid))
    }
  }

  private def success(reply: Any = ()) =
    sender ! reply

  private def add(p: PersistentImpl) = messages = messages + (messages.get(p.processorId) match {
    case Some(ms) ⇒ p.processorId -> (ms :+ p)
    case None     ⇒ p.processorId -> Vector(p)
  })

  private def update(pid: String, snr: Long)(f: PersistentImpl ⇒ PersistentImpl) = messages = messages.get(pid) match {
    case Some(ms) ⇒ messages + (pid -> ms.map(sp ⇒ if (sp.sequenceNr == snr) f(sp) else sp))
    case None     ⇒ messages
  }

  private def maxSequenceNr(pid: String): Long = {
    val snro = for {
      ms ← messages.get(pid)
      m ← ms.lastOption
    } yield m.sequenceNr
    snro.getOrElse(0L)
  }
}

private[persistence] object InmemStore {
  case class Write(p: PersistentImpl)
  case class Delete(p: PersistentImpl)
  case class Confirm(processorId: String, sequenceNr: Long, channelId: String)
  case class Replay(processorId: String, fromSequenceNr: Long, toSequenceNr: Long, replayCallback: (PersistentImpl) ⇒ Unit)
}
