/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.journal.inmem

import akka.actor.ActorRef
import akka.pattern.ask

import scala.collection.immutable
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try
import akka.persistence.journal.AsyncWriteJournal
import akka.persistence.PersistentRepr
import akka.persistence.AtomicWrite
import akka.util.Timeout

import scala.concurrent.duration._

/**
 * INTERNAL API — used to implement Persistence Query for testing purposes only.
 */
private[persistence] object InmemJournal {

  final case class SetDownstreamReceiver(target: ActorRef)
  case object RemoveDownstreamReceiver

  case object Ping // replies akka.Done
  case object SpoolExistingMessages

  final case class SpooledMessages(messages: Iterable[PersistentRepr])

  final case class DeleteSpooledMessages(persistenceId: String, toSequenceNr: Long) // replies akka.Done
}

/**
 * INTERNAL API.
 *
 * In-memory journal for testing purposes only.
 */
private[persistence] class InmemJournal extends AsyncWriteJournal with InmemMessages {
  import InmemJournal._

  override def asyncWriteMessages(messages: immutable.Seq[AtomicWrite]): Future[immutable.Seq[Try[Unit]]] = {
    for (w ← messages; p ← w.payload)
      add(p)
    Future.successful(Nil) // all good
  }

  override def asyncReadHighestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long] = {
    Future.successful(highestSequenceNr(persistenceId))
  }

  override def asyncReplayMessages(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long)(
    recoveryCallback: PersistentRepr ⇒ Unit): Future[Unit] = {
    val highest = highestSequenceNr(persistenceId)
    if (highest != 0L && max != 0L)
      read(persistenceId, fromSequenceNr, math.min(toSequenceNr, highest), max).foreach(recoveryCallback)
    Future.successful(())
  }

  def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long): Future[Unit] = {
    implicit def executionContext: ExecutionContext = context.dispatcher

    val toSeqNr = math.min(toSequenceNr, highestSequenceNr(persistenceId))
    var snr = 1L
    while (snr <= toSeqNr) {
      delete(persistenceId, snr)
      snr += 1
    }

    for {
      downstream ← sendDownstreamDeletion(persistenceId, toSeqNr)
    } yield {
      ()
    }
  }

  /* support for diverting messages to a downstream receiver: */

  private var _downstreamReceiver: Option[ActorRef] = None

  protected def sendDownstream(messages: ⇒ Iterable[PersistentRepr]): Unit =
    _downstreamReceiver.foreach(_ ! SpooledMessages(messages))

  protected def sendDownstreamDeletion(persistenceId: String, toSequenceNr: Long): Future[Unit] = {
    implicit val timeout: Timeout = Timeout(21474835L, SECONDS) // FIXME
    implicit def executionContext: ExecutionContext = context.dispatcher

    _downstreamReceiver match {
      case Some(target) ⇒ (target ? DeleteSpooledMessages(persistenceId, toSequenceNr)).map(_ ⇒ ())
      case None         ⇒ Future.successful()
    }
  }

  override def receivePluginInternal: Receive = super.receivePluginInternal orElse {
    case SetDownstreamReceiver(target) ⇒
      _downstreamReceiver = Some(target)

    case RemoveDownstreamReceiver ⇒
      _downstreamReceiver = None

    case SpoolExistingMessages ⇒
      sendDownstream(messages.values.reduce(_ ++ _).sortBy(_.sequenceNr))

    case Ping ⇒
      sender() ! akka.Done // this is just an "ordering" marker as
  }

  override def add(p: PersistentRepr): Unit = {
    super.add(p)
    sendDownstream(p :: Nil)
  }

  override def update(pid: String, snr: Long)(f: PersistentRepr ⇒ PersistentRepr): Unit = {
    _downstreamReceiver match {
      case Some(tap) ⇒ throw new UnsupportedOperationException("cannot update messages in active tap mode") // smell: Refused Bequest
      case None      ⇒ super.update(pid, snr)(f)
    }
  }

  override def delete(pid: String, snr: Long): Unit = super.delete(pid, snr)
}

/**
 * INTERNAL API.
 */
private[persistence] trait InmemMessages {
  // persistenceId -> persistent message
  var messages = Map.empty[String, Vector[PersistentRepr]]

  def add(p: PersistentRepr): Unit = messages = messages + (messages.get(p.persistenceId) match {
    case Some(ms) ⇒ p.persistenceId → (ms :+ p)
    case None     ⇒ p.persistenceId → Vector(p)
  })

  def update(pid: String, snr: Long)(f: PersistentRepr ⇒ PersistentRepr): Unit = messages = messages.get(pid) match {
    case Some(ms) ⇒ messages + (pid → ms.map(sp ⇒ if (sp.sequenceNr == snr) f(sp) else sp))
    case None     ⇒ messages
  }

  def delete(pid: String, snr: Long): Unit = messages = messages.get(pid) match {
    case Some(ms) ⇒ messages + (pid → ms.filterNot(_.sequenceNr == snr))
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

