package akka.persistence.testkit.journal

import java.io.IOException

import akka.actor.ActorSystem
import akka.persistence.journal.AsyncWriteTarget.ReplayMessages
import akka.persistence.journal.inmem.InmemJournal
import akka.persistence.journal.{ AsyncWriteJournal, AsyncWriteProxy }
import akka.persistence.{ AtomicWrite, PersistentRepr }
import akka.testkit.TestKitBase
import akka.util.Timeout

import scala.collection.immutable
import scala.concurrent.Future

abstract class TestkitJournal extends InmemJournal {

  override def receivePluginInternal: Receive = {
    case GetRefToJournal ⇒
      sender ! JournalRef(this)
  }
}

case object GetRefToJournal

case class JournalRef(ref: TestkitJournal)

class FailingJournal extends AsyncWriteJournal {

  val failWith = new IOException("Emulating persistence io failure.")

  override def asyncWriteMessages(messages: immutable.Seq[AtomicWrite]) = Future.failed(failWith)

  override def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long) = Future.failed(failWith)

  override def asyncReplayMessages(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long)(recoveryCallback: (PersistentRepr) ⇒ Unit) = Future.failed(failWith)

  override def asyncReadHighestSequenceNr(persistenceId: String, fromSequenceNr: Long) = Future.failed(failWith)

}

