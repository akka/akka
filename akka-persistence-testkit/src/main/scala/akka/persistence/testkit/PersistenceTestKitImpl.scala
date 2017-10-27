package akka.persistence.testkit

import java.util.UUID

import akka.actor.{ ActorSystem, Props }
import akka.persistence.{ AtomicWrite, Persistence, PersistentRepr }
import akka.persistence.testkit.journal.{ GetRefToJournal, JournalRef, TestkitJournal }
import akka.testkit.TestKitBase
import com.typesafe.config.ConfigFactory
import akka.pattern.ask
import akka.persistence.journal.AsyncWriteJournal
import akka.persistence.journal.inmem.InmemJournal
import akka.util.Timeout

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import scala.collection.{ immutable, mutable }

class PersistenceTestKitImpl extends PersistenceTestKit {

  override def expectPersisted(msg: Any): Unit = {
    println(map)
  }

  override def expectPersistedInOrder(msgs: immutable.Seq[Any]): Unit = ???

  override def expectPersistedInAnyOrder(msgs: immutable.Seq[Any]): Unit = ???

  override def recoverWith(persistenceId: String, msgs: immutable.Seq[Any]): Unit = ???
}

trait PersistenceTestKit extends TestKitBase with PersistentTestKitOps {

  implicit def timeout: Timeout = Timeout(1.minute)

  override implicit lazy val system = {
    //todo implement method for setting plugin in Persistence for testing purposes

    ActorSystem(s"persistence-testkit-${UUID.randomUUID()}", PersistenceTestKitPlugin.DefaultConf)

  }

  implicit val ec = system.dispatcher

  val map: mutable.Map[String, Vector[PersistentRepr]] = mutable.Map.empty

  val journal = Persistence(system).addNewJournal(() ⇒ system.actorOf(Props(classOf[PersistenceTestKitPlugin], map)), PersistenceTestKitPlugin.PluginId)

}

trait PersistentTestKitOps {

  def expectPersisted(msg: Any)

  def expectPersistedInOrder(msgs: immutable.Seq[Any])

  def expectPersistedInAnyOrder(msgs: immutable.Seq[Any])

  def recoverWith(persistenceId: String, msgs: immutable.Seq[Any])

}

class PersistenceTestKitPlugin(val map: mutable.Map[String, Vector[PersistentRepr]]) extends AsyncWriteJournal {

  override def asyncWriteMessages(messages: immutable.Seq[AtomicWrite]) = {
    for (w ← messages; p ← w.payload)
      add(p)
    Future.successful(Nil)
  }

  def add(p: PersistentRepr): Unit = map += (map.get(p.persistenceId) match {
    case Some(ms) ⇒ p.persistenceId → (ms :+ p)
    case None     ⇒ p.persistenceId → Vector(p)
  })

  override def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long) = ???

  override def asyncReplayMessages(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long)(recoveryCallback: (PersistentRepr) ⇒ Unit) = ???

  override def asyncReadHighestSequenceNr(persistenceId: String, fromSequenceNr: Long) = Future.successful(0)
}

object PersistenceTestKitPlugin {

  val PluginId = "persistence-testkit"

  val DefaultConf = ConfigFactory.parseString(
    s"""
       |akka.persistence.journal.plugin = "${PersistenceTestKitPlugin.PluginId}"
       |
        |# Class name of the plugin.
       |  ${PersistenceTestKitPlugin.PluginId}.class = "${classOf[PersistenceTestKitPlugin].getName}"
       |
        |
      """.stripMargin).withFallback(ConfigFactory.defaultApplication()).withFallback(ConfigFactory.defaultReference())

}

