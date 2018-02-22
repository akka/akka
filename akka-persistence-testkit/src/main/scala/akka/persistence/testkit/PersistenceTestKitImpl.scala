package akka.persistence.testkit

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiFunction

import akka.actor.{ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import akka.persistence.{AtomicWrite, PersistentRepr}
import com.typesafe.config.{Config, ConfigFactory}
import akka.persistence.journal.AsyncWriteJournal

import scala.annotation.tailrec
import scala.concurrent.Future
import scala.collection.immutable
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

trait PersistenceTestKit extends PersistentTestKitOps with UtilityAssertions {

  implicit lazy val system = {
    //todo probably implement method for setting plugin in Persistence for testing purposes
    ActorSystem(
      s"persistence-testkit-${UUID.randomUUID()}",
      PersistenceTestKitPlugin.PersitenceTestkitPluginConfig
        .withFallback(ConfigFactory.defaultApplication()))
  }

  implicit val ec = system.dispatcher

  private final lazy val storage = system.extension(InMemStorageExtension)

  //todo needs to be thread safe (atomic read-increment-write) for parallel tests?
  @volatile
  private var nextIndexByPersistenceId: immutable.Map[String, Int] = Map.empty

  override def expectNextPersisted(persistenceId: String, msg: Any): Unit = {

    val nextInd = nextIndexByPersistenceId.getOrElse(persistenceId, 0)
    val expected = Some(msg)
    awaitAssert({
      val actual = storage.findOneByIndex(persistenceId, nextInd).map(_.payload)
      assert(actual == expected, s"Failed to persist $msg, got $actual instead")
    })

    nextIndexByPersistenceId += (persistenceId -> (nextInd + 1))

  }

  override def persistForRecovery(persistenceId: String, msgs: immutable.Seq[Any]): Unit = {
    storage.addRaw(persistenceId, msgs)
    nextIndexByPersistenceId += persistenceId -> (nextIndexByPersistenceId.getOrElse(persistenceId, 0) + msgs.size)
  }


  override def expectPersistedInOrder(persistenceId: String, msgs: immutable.Seq[Any]): Unit = {
    val nextInd = nextIndexByPersistenceId.getOrElse(persistenceId, 0)
    awaitAssert({
      val actual = storage.findMany(persistenceId, nextInd, msgs.size)
    })
  }

  override def expectPersistedInAnyOrder(persistenceId: String, msgs: immutable.Seq[Any]): Unit = ???

  def withRecoveryPolicy(policy: ProcessingPolicy) = storage.setRecoveryPolicy(policy)

  def withWritingPolicy(policy: ProcessingPolicy) = storage.setWritingPolicy(policy)

  def rejectNextPersisted(persistenceId: String) = ???

  def rejectNextPersisted() = ???

  def failNextPersisted(persistenceId: String) = ???

  def failNextPersisted() = ???

  override def clearAll(): Unit = storage.clearAll()

}


trait UtilityAssertions {

  import scala.concurrent.duration._

  protected val DefaultExpectOnePersistedTimeout: FiniteDuration

  def awaitAssert[A](a: ⇒ A): A =
    awaitAssert(a, None)

  def awaitAssert[A](a: ⇒ A, max: FiniteDuration, interval: Duration = 100.millis): A =
    awaitAssert(a, Some(max), interval)

  protected def now: FiniteDuration = System.nanoTime.nanos

  private def awaitAssert[A](a: ⇒ A, max: Option[FiniteDuration] = None, interval: Duration = 100.millis): A = {
    val _max = max.getOrElse(DefaultExpectOnePersistedTimeout)
    val stop = now + _max

    @tailrec
    def poll(t: Duration): A = {
      // cannot use null-ness of result as signal it failed
      // because Java API and not wanting to return a value will be "return null"
      var failed = false
      val result: A =
        try {
          val aRes = a
          failed = false
          aRes
        } catch {
          case NonFatal(e) ⇒
            failed = true
            if ((now + t) >= stop) throw e
            else null.asInstanceOf[A]
        }

      if (!failed) result
      else {
        Thread.sleep(t.toMillis)
        poll((stop - now) min interval)
      }
    }

    poll(_max min interval)
  }


}

trait InMemStorage {

  private final val eventsMap: ConcurrentHashMap[String, Vector[PersistentRepr]] = new ConcurrentHashMap()

  def findMany(persistenceId: String, fromInclusive: Int, maxNum: Int): Option[Vector[PersistentRepr]] =
    Option(eventsMap.get(persistenceId))
      .flatMap(value => if(value.size > fromInclusive) Some(value.drop(fromInclusive).take(maxNum)) else None)

  def findOneByIndex(persistenceId: String, index: Int): Option[PersistentRepr] =
    Option(eventsMap.get(persistenceId))
      .flatMap(value ⇒ if (value.size > index) Some(value(index)) else None)

  def addRaw(persistenceId: String, e: Any): Unit =
    addRaw(persistenceId, immutable.Seq(e))

  def addRaw(persistenceId: String, e: immutable.Seq[Any]): Unit =
    eventsMap.compute(persistenceId, (_: String, value: Vector[PersistentRepr]) ⇒ {
      Option(value).map(v ⇒ {
        val start = v.lastOption.map(_.sequenceNr).getOrElse(0)
        v ++ e.zipWithIndex.map(p ⇒ PersistentRepr(p._1, p._2 + start, persistenceId))
      }).getOrElse(e.zipWithIndex.map(p ⇒ PersistentRepr(p._1, p._2, persistenceId))).toVector
    })

  def add(p: PersistentRepr): Unit =
    add(List(p))

  def add(elems: immutable.Seq[PersistentRepr]): Unit =
    elems
      .groupBy(_.persistenceId)
      .foreach(pair ⇒ {
        eventsMap.compute(pair._1, (_: String, value: Vector[PersistentRepr]) ⇒ value match {
          case null ⇒ pair._2.toVector
          case existing ⇒ existing ++ pair._2
        })
      })

  def deleteToSeqNumber(persistenceId: String, toSeqNumberInclusive: Long): Unit =
    eventsMap.computeIfPresent(persistenceId, (_: String, value: Vector[PersistentRepr]) ⇒ {
      value.dropWhile(_.sequenceNr <= toSeqNumberInclusive)
    })

  def read(persistenceId: String, fromInclusive: Long, toInclusive: Long, maxNumber: Long): immutable.Seq[PersistentRepr] =
    eventsMap.getOrDefault(persistenceId, Vector.empty)
      .dropWhile(_.sequenceNr < fromInclusive)
      //we dont need to read highestSeqNumber because it will in any case stop at it if toInclusive > highestSeqNumber
      .takeWhile(_.sequenceNr <= toInclusive)
      .take(maxNumber.toInt)

  def readHighestSequenceNum(persistenceId: String) =
    eventsMap.computeIfAbsent(persistenceId, (_: String) ⇒ Vector.empty[PersistentRepr])
      .lastOption
      .map(_.sequenceNr)
      .getOrElse(0L)

  def clearAll() = eventsMap.clear()

  def clearByPersistenceId(persistenceId: String) = ???

  import java.util.{function ⇒ jf}
  import scala.language.implicitConversions

  private implicit def scalaFun1ToJava[T, R](f: T ⇒ R): jf.Function[T, R] = new jf.Function[T, R] {
    override def apply(t: T): R = f(t)
  }

  private implicit def scalaFun2ToJava[T, M, R](f: (T, M) ⇒ R): jf.BiFunction[T, M, R] = new BiFunction[T, M, R] {
    override def apply(t: T, u: M): R = f(t, u)
  }

}

trait InMemStorageEmulator extends InMemStorage {
  import ProcessingPolicy._

  @volatile private var writingPolicy: ProcessingPolicy = ProcessingPolicy.PassAll
  @volatile private var recoveryPolicy: ProcessingPolicy = ProcessingPolicy.PassAll

  /**
   *
   * @throws exception from StorageFailure in the current writing policy
   */
  def tryAdd(elems: immutable.Seq[PersistentRepr]): Try[Unit] = {
    writingPolicy.tryProcess(elems.map(_.payload)) match {
      case ProcessingSuccess ⇒
        add(elems)
        Success(())
      case Reject(ex) ⇒ Failure(ex)
      case StorageFailure(ex) ⇒ throw ex
    }
  }

  def tryRead(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long): immutable.Seq[PersistentRepr] = {
    val batch = read(persistenceId, fromSequenceNr, toSequenceNr, max)
    recoveryPolicy.tryProcess(batch) match {
      case ProcessingSuccess ⇒ batch
      case Reject(ex) ⇒ throw ex
      case StorageFailure(ex) ⇒ throw ex
    }
  }

  def setWritingPolicy(policy: ProcessingPolicy) = writingPolicy = policy

  def setRecoveryPolicy(policy: ProcessingPolicy) = recoveryPolicy = policy

}


trait InMemEmulatorExtension extends InMemStorageEmulator with Extension

object InMemStorageExtension extends ExtensionId[InMemEmulatorExtension] with ExtensionIdProvider {

  override def createExtension(system: ExtendedActorSystem) = new InMemEmulatorExtension {}

  override def lookup() = InMemStorageExtension

}

trait PersistentTestKitOps {

  def expectNextPersisted(persistenceId: String, msg: Any): Any

  def expectPersistedInOrder(persistenceId: String, msgs: immutable.Seq[Any])

  def expectPersistedInAnyOrder(persistenceId: String, msgs: immutable.Seq[Any])

  def rejectNextPersisted(persistenceId: String): Unit

  def rejectNextPersisted(): Unit

  def failNextPersisted(persistenceId: String): Unit

  def failNextPersisted(): Unit

  def persistForRecovery(persistenceId: String, msgs: immutable.Seq[Any]): Unit

  def clearAll(): Unit

  def clearByPersistenceId(persistenceId: String)

}

class PersistenceTestKitPlugin extends AsyncWriteJournal {

  private final val storage = InMemStorageExtension(context.system)

  private implicit val ec = context.system.dispatcher

  override def asyncWriteMessages(messages: immutable.Seq[AtomicWrite]): Future[immutable.Seq[Try[Unit]]] =
    Future.fromTry(Try(messages.map(aw => storage.tryAdd(aw.payload))))


  override def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long): Future[Unit] =
    //todo should we emulate exception on delete?
    Future.successful(storage.deleteToSeqNumber(persistenceId, toSequenceNr))

  override def asyncReplayMessages(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long)(recoveryCallback: (PersistentRepr) ⇒ Unit): Future[Unit] =
    Future.fromTry(Try(storage.tryRead(persistenceId, fromSequenceNr, toSequenceNr, max).foreach(recoveryCallback)))

  override def asyncReadHighestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long] =
    //todo should we emulate exception on readSeqNumber?
    Future.successful(storage.readHighestSequenceNum(persistenceId))

}

object PersistenceTestKitPlugin {

  val PluginId = "persistence.testkit.plugin"

  import scala.collection.JavaConverters._

  val PersitenceTestkitPluginConfig: Config = ConfigFactory.parseMap(
    Map(
      "akka.persistence.journal.plugin" -> PluginId,
      s"$PluginId.class" -> s"${classOf[PersistenceTestKitPlugin].getName}"
    ).asJava
  )

}

object PersistenceTestExtensionId extends ExtensionId[PersistenceTestKitSettings]{
  import PersistenceTestKitSettings._

  override def createExtension(system: ExtendedActorSystem): PersistenceTestKitSettings =
    new PersistenceTestKitSettings(system.settings.config.getConfig(configPath))

}


class PersistenceTestKitSettings(config: Config) extends Extension {

  import akka.util.Helpers._

  val assertTimeout = config.getMillisDuration("timeout")


}

object PersistenceTestKitSettings{
  val configPath = "akka.persistence.testkit"
}

