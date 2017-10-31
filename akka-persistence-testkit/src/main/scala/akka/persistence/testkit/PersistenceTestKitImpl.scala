package akka.persistence.testkit

import java.util.UUID
import java.util.concurrent.{ BlockingDeque, ConcurrentHashMap, LinkedBlockingDeque }
import java.util.function.BiFunction

import akka.actor.{ ActorSystem, Props }
import akka.persistence.{ AtomicWrite, Persistence, PersistentRepr }
import akka.testkit.TestKitBase
import com.typesafe.config.ConfigFactory
import akka.persistence.journal.AsyncWriteJournal
import akka.util.Timeout

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.collection.{ immutable }

trait PersistenceTestKit extends TestKitBase with PersistentTestKitOps {

  implicit def timeout: Timeout = Timeout(10.seconds)

  override implicit lazy val system = {
    //todo implement method for setting plugin in Persistence for testing purposes

    ActorSystem(s"persistence-testkit-${UUID.randomUUID()}", PersistenceTestKitPlugin.DefaultConf)

  }

  implicit val ec = system.dispatcher

  final val map: ConcurrentHashMap[String, BlockingDeque[PersistentRepr]] = new ConcurrentHashMap()

  val journal = Persistence(system).addNewJournal(() ⇒ system.actorOf(Props(classOf[PersistenceTestKitPlugin], map)), PersistenceTestKitPlugin.PluginId)

  override def expectNextPersisted(persistenceId: String, msg: Any): Unit = {
    val actual = receiveOnePersisted(persistenceId)
    assert(actual == msg, s"Failed to persist $msg, got $actual instead")
  }

  override def recoverWith(persistenceId: String, msgs: immutable.Seq[Any]): Unit = ???

  override def expectPersistedInOrder(persistenceId: String, msgs: immutable.Seq[Any]): Unit = ???

  override def expectPersistedInAnyOrder(persistenceId: String, msgs: immutable.Seq[Any]): Unit = ???

  private def receiveOnePersisted(persistenceId: String): Any = {
    val msg = map.computeIfAbsent(persistenceId, new java.util.function.Function[String, BlockingDeque[PersistentRepr]] {
      override def apply(v1: String) = {
        new LinkedBlockingDeque[PersistentRepr]()
      }
    })
      .pollFirst(timeout.duration.length, timeout.duration.unit)
      .payload
    msg
  }

}

trait PersistentTestKitOps {

  def expectNextPersisted(peristenceId: String, msg: Any)

  def expectPersistedInOrder(persistenceId: String, msgs: immutable.Seq[Any])

  def expectPersistedInAnyOrder(persistenceId: String, msgs: immutable.Seq[Any])

  def recoverWith(persistenceId: String, msgs: immutable.Seq[Any])

}

class PersistenceTestKitPlugin(val map: ConcurrentHashMap[String, BlockingDeque[PersistentRepr]]) extends AsyncWriteJournal {

  override def asyncWriteMessages(messages: immutable.Seq[AtomicWrite]) = {
    for (w ← messages; p ← w.payload)
      add(p)
    Future.successful(Nil)
  }

  def add(p: PersistentRepr): Unit = map.compute(p.persistenceId, new BiFunction[String, BlockingDeque[PersistentRepr], BlockingDeque[PersistentRepr]] {
    override def apply(t: String, u: BlockingDeque[PersistentRepr]) = u match {
      case null ⇒
        val q = new LinkedBlockingDeque[PersistentRepr]()
        q.add(p)
        q
      case existing ⇒
        existing.add(p)
        existing
    }
  })

  override def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long) = ???

  override def asyncReplayMessages(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long)(recoveryCallback: (PersistentRepr) ⇒ Unit) = ???

  override def asyncReadHighestSequenceNr(persistenceId: String, fromSequenceNr: Long) = {
    Future.successful(Option(map.computeIfAbsent(persistenceId, new java.util.function.Function[String, BlockingDeque[PersistentRepr]] {
      override def apply(v1: String) = {
        new LinkedBlockingDeque[PersistentRepr]()
      }
    }).pollFirst()).map(_.sequenceNr).getOrElse(0L))
  }

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

