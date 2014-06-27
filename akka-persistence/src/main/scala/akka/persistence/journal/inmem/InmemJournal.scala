/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.journal.inmem

import scala.collection.immutable
import scala.concurrent.duration._
import scala.language.postfixOps

import akka.actor._
import akka.persistence._
import akka.persistence.journal.AsyncWriteProxy
import akka.persistence.journal.AsyncWriteTarget
import akka.util.Timeout

/**
 * INTERNAL API.
 *
 * In-memory journal for testing purposes only.
 */
private[persistence] class InmemJournal extends AsyncWriteProxy {
  import AsyncWriteProxy.SetStore

  val timeout = Timeout(5 seconds)

  override def preStart(): Unit = {
    super.preStart()
    self ! SetStore(context.actorOf(Props[InmemStore]))
  }
}

/**
 * INTERNAL API.
 */
private[persistence] trait InmemMessages {
  // persistenceId -> persistent message
  var messages = Map.empty[String, Vector[PersistentRepr]]

  def add(p: PersistentRepr) = messages = messages + (messages.get(p.persistenceId) match {
    case Some(ms) ⇒ p.persistenceId -> (ms :+ p)
    case None     ⇒ p.persistenceId -> Vector(p)
  })

  def update(pid: String, snr: Long)(f: PersistentRepr ⇒ PersistentRepr) = messages = messages.get(pid) match {
    case Some(ms) ⇒ messages + (pid -> ms.map(sp ⇒ if (sp.sequenceNr == snr) f(sp) else sp))
    case None     ⇒ messages
  }

  def delete(pid: String, snr: Long) = messages = messages.get(pid) match {
    case Some(ms) ⇒ messages + (pid -> ms.filterNot(_.sequenceNr == snr))
    case None     ⇒ messages
  }

  def read(pid: String, fromSnr: Long, toSnr: Long, max: Long): immutable.Seq[PersistentRepr] = messages.get(pid) match {
    case Some(ms) ⇒ ms.filter(m ⇒ m.sequenceNr >= fromSnr && m.sequenceNr <= toSnr).take(safeLongToInt(max))
    case None     ⇒ Nil
  }

  def highestSequenceNr(pid: String): Long = {
    val snro = for {
      ms ← messages.get(pid)
      m ← ms.lastOption
    } yield m.sequenceNr
    snro.getOrElse(0L)
  }

  private def safeLongToInt(l: Long): Int =
    if (Int.MaxValue < l) Int.MaxValue else l.toInt
}

/**
 * INTERNAL API.
 */
private[persistence] class InmemStore extends Actor with InmemMessages {
  import AsyncWriteTarget._

  def receive = {
    case WriteMessages(msgs) ⇒
      sender ! msgs.foreach(add)
    case WriteConfirmations(cnfs) ⇒
      sender ! cnfs.foreach(cnf ⇒ update(cnf.persistenceId, cnf.sequenceNr)(p ⇒ p.update(confirms = cnf.channelId +: p.confirms)))
    case DeleteMessages(msgIds, false) ⇒
      sender ! msgIds.foreach(msgId ⇒ update(msgId.persistenceId, msgId.sequenceNr)(_.update(deleted = true)))
    case DeleteMessages(msgIds, true) ⇒
      sender ! msgIds.foreach(msgId ⇒ delete(msgId.persistenceId, msgId.sequenceNr))
    case DeleteMessagesTo(pid, tsnr, false) ⇒
      sender ! (1L to tsnr foreach { snr ⇒ update(pid, snr)(_.update(deleted = true)) })
    case DeleteMessagesTo(pid, tsnr, true) ⇒
      sender ! (1L to tsnr foreach { snr ⇒ delete(pid, snr) })
    case ReplayMessages(pid, fromSnr, toSnr, max) ⇒
      read(pid, fromSnr, toSnr, max).foreach(sender ! _)
      sender ! ReplaySuccess
    case ReadHighestSequenceNr(persistenceId, _) ⇒
      sender ! highestSequenceNr(persistenceId)
  }
}
