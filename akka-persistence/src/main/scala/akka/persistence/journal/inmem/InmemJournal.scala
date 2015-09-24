/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.journal.inmem

import scala.collection.immutable
import scala.concurrent.duration._
import scala.language.postfixOps
import akka.actor._
import akka.persistence._
import akka.persistence.journal.AsyncWriteJournal
import akka.persistence.journal.{ WriteJournalBase, AsyncWriteProxy, AsyncWriteTarget }
import akka.util.Timeout
import scala.util.Try

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

  def add(p: PersistentRepr): Unit = messages = messages + (messages.get(p.persistenceId) match {
    case Some(ms) ⇒ p.persistenceId -> (ms :+ p)
    case None     ⇒ p.persistenceId -> Vector(p)
  })

  def update(pid: String, snr: Long)(f: PersistentRepr ⇒ PersistentRepr): Unit = messages = messages.get(pid) match {
    case Some(ms) ⇒ messages + (pid -> ms.map(sp ⇒ if (sp.sequenceNr == snr) f(sp) else sp))
    case None     ⇒ messages
  }

  def delete(pid: String, snr: Long): Unit = messages = messages.get(pid) match {
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
private[persistence] class InmemStore extends Actor with InmemMessages with WriteJournalBase {
  import AsyncWriteTarget._

  def receive = {
    case WriteMessages(msgs) ⇒
      val results: immutable.Seq[Try[Unit]] =
        for (a ← msgs) yield {
          Try(a.payload.foreach(add))
        }
      sender() ! results
    case DeleteMessagesTo(pid, tsnr) ⇒
      val toSeqNr = math.min(tsnr, highestSequenceNr(pid))
      var snr = 1L
      while (snr <= toSeqNr) {
        delete(pid, snr)
        snr += 1
      }
      sender().tell((), self)
    case ReplayMessages(pid, fromSnr, toSnr, max) ⇒
      val highest = highestSequenceNr(pid)
      if (highest != 0L && max != 0L)
        read(pid, fromSnr, math.min(toSnr, highest), max).foreach { sender() ! _ }
      sender() ! ReplaySuccess(highest)
  }
}
