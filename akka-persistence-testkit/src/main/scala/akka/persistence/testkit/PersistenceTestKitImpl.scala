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
import scala.collection.immutable

trait PersistenceTestKit extends TestKitBase with PersistentTestKitOps {

  implicit def timeout: Timeout = Timeout(10.seconds)

  override implicit lazy val system = {
    //todo implement method for setting plugin in Persistence for testing purposes

    ActorSystem(s"persistence-testkit-${UUID.randomUUID()}", PersistenceTestKitPlugin.DefaultConf)

  }

  implicit val ec = system.dispatcher

  final val map: ConcurrentHashMap[String, BlockingDeque[PersistentRepr]] = new ConcurrentHashMap()

  //todo this should be atomicref
  final val decider: RejectionDecider = new RejectionDecider(new RejectionPolicy {
    override def rejectOrPass(msg: Any) = PassMessage
  })

  val journal = Persistence(system).addNewJournal(() ⇒ system.actorOf(Props(classOf[PersistenceTestKitPlugin], map, decider)), PersistenceTestKitPlugin.PluginId)

  override def expectNextPersisted(persistenceId: String, msg: Any): Unit = {
    val actual = receiveOnePersisted(persistenceId)
    assert(actual == msg, s"Failed to persist $msg, got $actual instead")
  }

  override def recoverWith(persistenceId: String, msgs: immutable.Seq[Any]): Unit = ???

  override def expectPersistedInOrder(persistenceId: String, msgs: immutable.Seq[Any]): Unit = ???

  override def expectPersistedInAnyOrder(persistenceId: String, msgs: immutable.Seq[Any]): Unit = ???

  override def withRejectionPolicy(rej: RejectionPolicy) = ???

  override def clearTheJournal() = {
    map.clear()
  }

  private def receiveOnePersisted(persistenceId: String): Any = {
    val msg = map.computeIfAbsent(persistenceId, new java.util.function.Function[String, BlockingDeque[PersistentRepr]] {
      override def apply(v1: String) = {
        new LinkedBlockingDeque[PersistentRepr]()
      }
    })
      //todo this can be changed to peek or find, not to delete the message from queue
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

  def clearTheJournal(): Unit

  //todo probably init new journal for each policy
  def withRejectionPolicy(rej: RejectionPolicy)

}

class PersistenceTestKitPlugin(val map: ConcurrentHashMap[String, BlockingDeque[PersistentRepr]], val decider: RejectionDecider) extends AsyncWriteJournal {

  override def asyncWriteMessages(messages: immutable.Seq[AtomicWrite]) = {
    var failed: Option[Throwable] = None
    for (w ← messages; p ← w.payload if failed.isEmpty) {
      decider.policy.rejectOrPass(p) match {
        case PassMessage ⇒
          add(p)
        case Reject(e) ⇒
          failed = Some(e)
      }
    }
    failed match {
      case None    ⇒ Future.successful(Nil)
      case Some(e) ⇒ Future.failed(e)
    }
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
    val result = Option(
      map.computeIfAbsent(persistenceId, new java.util.function.Function[String, BlockingDeque[PersistentRepr]] {
        override def apply(v1: String) = {
          new LinkedBlockingDeque[PersistentRepr]()
        }
      }).pollFirst()).map(_.sequenceNr).getOrElse(0L)
    Future.successful(result)
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

