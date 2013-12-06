/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
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
  import AsyncWriteTarget._

  def receive = {
    case WriteBatch(pb) ⇒
      sender ! pb.foreach(add)
    case Delete(pid, fsnr, tsnr, false) ⇒
      sender ! (fsnr to tsnr foreach { snr ⇒ update(pid, snr)(_.update(deleted = true)) })
    case Delete(pid, fsnr, tsnr, true) ⇒
      sender ! (fsnr to tsnr foreach { snr ⇒ delete(pid, snr) })
    case Confirm(pid, snr, cid) ⇒
      sender ! update(pid, snr)(p ⇒ p.update(confirms = cid +: p.confirms))
    case Replay(pid, fromSnr, toSnr) ⇒
      read(pid, fromSnr, toSnr).foreach(sender ! _)
      sender ! ReplaySuccess(maxSequenceNr(pid))
  }
}
