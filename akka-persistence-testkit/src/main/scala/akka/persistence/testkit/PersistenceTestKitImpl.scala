package akka.persistence.testkit

import java.util.UUID
import java.util.concurrent.{ConcurrentHashMap}
import java.util.function.BiFunction

import akka.actor.{ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import akka.persistence.{AtomicWrite, PersistentRepr}
import akka.testkit.TestKitBase
import com.typesafe.config.ConfigFactory
import akka.persistence.journal.AsyncWriteJournal

import scala.concurrent.Future
import scala.collection.immutable
import scala.util.{Success, Try}

trait PersistenceTestKit extends TestKitBase with PersistentTestKitOps {

  override implicit lazy val system = {
    //todo implement method for setting plugin in Persistence for testing purposes

    ActorSystem(s"persistence-testkit-${UUID.randomUUID()}", PersistenceTestKitPlugin.DefaultConf)

  }

  implicit val ec = system.dispatcher

  private final lazy val storage = system.extension(InMemStorageExtension)

  //todo probably needs to be thread safe (AtomicRef)
  private var nextIndexByPersistenceId: immutable.Map[String, Int] = Map.empty

  override def expectNextPersisted(persistenceId: String, msg: Any): Unit = {

    val nextInd = nextIndexByPersistenceId.getOrElse(persistenceId, 0)
    val expected = Some(msg)
    awaitAssert({
      val actual = storage.findOneByIndex(persistenceId, nextInd).map(_.payload)
      assert(actual == expected, s"Failed to persist $msg, got $actual instead")
    }, testKitSettings.SingleExpectDefaultTimeout)

    nextIndexByPersistenceId += (persistenceId -> (nextInd + 1))

  }

  override def recoverWith(persistenceId: String, msgs: immutable.Seq[Any]): Unit = ???

  override def expectPersistedInOrder(persistenceId: String, msgs: immutable.Seq[Any]): Unit = ???

  override def expectPersistedInAnyOrder(persistenceId: String, msgs: immutable.Seq[Any]): Unit = ???

  override def withRejectionPolicy(rej: RejectionPolicy) = ???

  override def clearAll(): Unit = storage.clearAll()

}

class InMemStorage extends Extension {

  private final val eventsMap: ConcurrentHashMap[String, Vector[PersistentRepr]] = new ConcurrentHashMap()

  def setByPeristenceId(persistenceId: String, elements: immutable.Seq[PersistentRepr]) =
    eventsMap.put(persistenceId, Vector(elements:_*))

  def findOneByIndex(persistenceId: String, index: Int): Option[PersistentRepr] =
    Option(eventsMap.get(persistenceId))
      .flatMap(value => if (value.size > index) Some(value(index)) else None)

  def add(p: PersistentRepr): Try[Unit] = {
    eventsMap.compute(p.persistenceId, (_: String, value: Vector[PersistentRepr]) => value match {
        case null ⇒ Vector(p)
        case existing ⇒ existing :+ p
      }
    )
    Success(())
  }

  def readHighestSequenceNum(persistenceId: String) =
    eventsMap.computeIfAbsent(persistenceId, (_: String) => Vector.empty[PersistentRepr])
      .headOption
      .map(_.sequenceNr)
      .getOrElse(0L)


  def clearAll() = eventsMap.clear()

  def clearByPersistenceId(persistenceId: String) = eventsMap.remove(persistenceId)

  import java.util.{function => jf}

  import scala.language.implicitConversions


  private implicit def scalaFun1ToJava[T, R](f: T => R): jf.Function[T,R] = new jf.Function[T, R] {
    override def apply(t: T): R = f(t)
  }

  private implicit def scalaFun2ToJava[T,M,R](f:(T,M) => R): jf.BiFunction[T,M,R] = new BiFunction[T,M,R] {
    override def apply(t: T, u: M): R = f(t,u)
  }

}


object InMemStorageExtension extends ExtensionId[InMemStorage] with ExtensionIdProvider {

  override def createExtension(system: ExtendedActorSystem) = new InMemStorage

  override def lookup() = InMemStorageExtension
}

trait PersistentTestKitOps {

  def expectNextPersisted(peristenceId: String, msg: Any)

  def expectPersistedInOrder(persistenceId: String, msgs: immutable.Seq[Any])

  def expectPersistedInAnyOrder(persistenceId: String, msgs: immutable.Seq[Any])

  def recoverWith(persistenceId: String, msgs: immutable.Seq[Any])

  def clearAll(): Unit

  //todo probably init new journal for each policy
  def withRejectionPolicy(rej: RejectionPolicy)

}

class PersistenceTestKitPlugin extends AsyncWriteJournal {

  private final val storage = InMemStorageExtension(context.system)

  override def asyncWriteMessages(messages: immutable.Seq[AtomicWrite]) = {

    for (w ← messages; p ← w.payload) {
      storage.add(p)
    }
    Future.successful(Nil)
  }

  override def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long) = ???

  override def asyncReplayMessages(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long)(recoveryCallback: (PersistentRepr) ⇒ Unit) = ???

  override def asyncReadHighestSequenceNr(persistenceId: String, fromSequenceNr: Long) = {
    val result = storage.readHighestSequenceNum(persistenceId)
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

