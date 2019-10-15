/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.journal.inmem

import scala.collection.immutable
import scala.concurrent.Future
import scala.util.Try
import scala.util.control.NonFatal

import akka.persistence.journal.AsyncWriteJournal
import akka.persistence.PersistentRepr
import akka.persistence.AtomicWrite
import akka.serialization.SerializationExtension
import akka.serialization.Serializers
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

/**
 * INTERNAL API.
 *
 * In-memory journal for testing purposes only.
 */
private[persistence] class InmemJournal(cfg: Config) extends AsyncWriteJournal with InmemMessages {

  def this() = this(ConfigFactory.empty())

  private val testSerialization = {
    val key = "test-serialization"
    if (cfg.hasPath(key)) cfg.getBoolean("test-serialization")
    else false
  }

  private val serialization = SerializationExtension(context.system)

  override def asyncWriteMessages(messages: immutable.Seq[AtomicWrite]): Future[immutable.Seq[Try[Unit]]] = {
    try {
      for (w <- messages; p <- w.payload) {
        verifySerialization(p.payload)
        add(p)
      }
      Future.successful(Nil) // all good
    } catch {
      case NonFatal(e) =>
        // serialization problem
        Future.failed(e)
    }
  }

  override def asyncReadHighestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long] = {
    Future.successful(highestSequenceNr(persistenceId))
  }

  override def asyncReplayMessages(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long)(
      recoveryCallback: PersistentRepr => Unit): Future[Unit] = {
    val highest = highestSequenceNr(persistenceId)
    if (highest != 0L && max != 0L)
      read(persistenceId, fromSequenceNr, math.min(toSequenceNr, highest), max).foreach(recoveryCallback)
    Future.successful(())
  }

  def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long): Future[Unit] = {
    val toSeqNr = math.min(toSequenceNr, highestSequenceNr(persistenceId))
    var snr = 1L
    while (snr <= toSeqNr) {
      delete(persistenceId, snr)
      snr += 1
    }
    Future.successful(())
  }

  private def verifySerialization(event: Any): Unit = {
    if (testSerialization) {
      val eventAnyRef = event.asInstanceOf[AnyRef]
      val bytes = serialization.serialize(eventAnyRef).get
      val serializer = serialization.findSerializerFor(eventAnyRef)
      val manifest = Serializers.manifestFor(serializer, eventAnyRef)
      serialization.deserialize(bytes, serializer.identifier, manifest).get
    }
  }
}

/**
 * INTERNAL API.
 */
private[persistence] trait InmemMessages {
  // persistenceId -> persistent message
  var messages = Map.empty[String, Vector[PersistentRepr]]
  // persistenceId -> highest used sequence number
  private var highestSequenceNumbers = Map.empty[String, Long]

  def add(p: PersistentRepr): Unit = {
    messages = messages + (messages.get(p.persistenceId) match {
        case Some(ms) => p.persistenceId -> (ms :+ p)
        case None     => p.persistenceId -> Vector(p)
      })
    highestSequenceNumbers =
      highestSequenceNumbers.updated(p.persistenceId, math.max(highestSequenceNr(p.persistenceId), p.sequenceNr))
  }

  def delete(pid: String, snr: Long): Unit = messages = messages.get(pid) match {
    case Some(ms) => messages + (pid -> ms.filterNot(_.sequenceNr == snr))
    case None     => messages
  }

  def read(pid: String, fromSnr: Long, toSnr: Long, max: Long): immutable.Seq[PersistentRepr] =
    messages.get(pid) match {
      case Some(ms) => ms.filter(m => m.sequenceNr >= fromSnr && m.sequenceNr <= toSnr).take(safeLongToInt(max))
      case None     => Nil
    }

  def highestSequenceNr(pid: String): Long = {
    highestSequenceNumbers.getOrElse(pid, 0L)
  }

  private def safeLongToInt(l: Long): Int =
    if (Int.MaxValue < l) Int.MaxValue else l.toInt
}
