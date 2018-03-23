/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.scaladsl

import java.util.UUID

import akka.actor.{ ActorSystem, ExtendedActorSystem, Extension, ExtensionId }
import akka.persistence.testkit.scaladsl.ProcessingPolicy.{ FailNextN, PassAll, RejectNextN }
import com.typesafe.config.{ Config, ConfigFactory }

import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

trait PersistenceTestKit extends PersistentTestKitOps with UtilityAssertions {
  import PersistenceTestKit._
  import scala.concurrent.duration._

  implicit lazy val system = {
    //todo probably implement method for setting plugin in Persistence for testing purposes
    ActorSystem(
      s"persistence-testkit-${UUID.randomUUID()}",
      PersistenceTestKitPlugin.PersitenceTestkitJournalConfig
        .withFallback(PersistenceTestKitSnapshotPlugin.PersitenceTestkitSnapshotStoreConfig)
        .withFallback(ConfigFactory.defaultApplication()))
  }

  implicit val ec = system.dispatcher

  private final lazy val storage = system.extension(InMemStorageExtension)
  private final val settings = SettingsExtension(system)

  //todo needs to be thread safe (atomic read-increment-write) for parallel tests?
  @volatile
  private var nextIndexByPersistenceId: immutable.Map[String, Int] = Map.empty

  override def expectNextPersisted(persistenceId: String, msg: Any): Unit = {

    val nextInd = nextIndexByPersistenceId.getOrElse(persistenceId, 0)
    val expected = Some(msg)
    awaitAssert({
      val actual = storage.findOneByIndex(persistenceId, nextInd).map(_.payload)
      assert(actual == expected, s"Failed to persist $msg, got $actual instead")
    }, max = settings.assertTimeout)

    nextIndexByPersistenceId += (persistenceId -> (nextInd + 1))

  }

  override def rejectNextPersisted(persistenceId: String): Unit = ???

  override def failNextPersisted(persistenceId: String): Unit = ???

  override def persistForRecovery(persistenceId: String, msgs: immutable.Seq[Any]): Unit = {
    storage.addAny(persistenceId, msgs)
    nextIndexByPersistenceId += persistenceId -> (nextIndexByPersistenceId.getOrElse(persistenceId, 0) + msgs.size)
  }

  override def expectPersistedInOrder(persistenceId: String, msgs: immutable.Seq[Any]): Unit =
    msgs.foreach(expectNextPersisted(persistenceId, _))

  override def expectPersistedInAnyOrder(persistenceId: String, msgs: immutable.Seq[Any]): Unit = {

    val nextInd = nextIndexByPersistenceId.getOrElse(persistenceId, 0)
    awaitAssert({
      val actual = storage.findMany(persistenceId, nextInd, msgs.size)
      actual match {
        case Some(reprs) ⇒
          val ls = reprs.map(_.payload)
          assert(ls.size == msgs.size && ls.diff(msgs).isEmpty, "Persisted messages do not correspond to expected ones")
        case None ⇒ assert(false, "No messages were persisted")
      }
    }, max = settings.assertTimeout)

    nextIndexByPersistenceId += (persistenceId -> (nextInd + msgs.size))

  }

  def withRecoveryPolicy(policy: ProcessingPolicy) = storage.setRecoveryPolicy(policy)

  def withWritingPolicy(policy: ProcessingPolicy) = storage.setWritingPolicy(policy)

  def rejectNextPersisted() = new RejectNextN(1, ExpectedRejection) {

    override def tryProcess(batch: immutable.Seq[Any]): ProcessingPolicy.ProcessingResult = {
      val r = super.tryProcess(batch)
      withWritingPolicy(PassAll)
      r
    }

  }

  def failNextPersisted() = new FailNextN(1, ExpectedFailure) {

    override def tryProcess(batch: immutable.Seq[Any]): ProcessingPolicy.ProcessingResult = {
      val r = super.tryProcess(batch)
      withWritingPolicy(PassAll)
      r
    }

  }

  override def clearAll(): Unit = storage.clearAll()

  override def clearByPersistenceId(persistenceId: String): Unit = storage.removeKey(persistenceId)

  private def awaitAssert[A](a: ⇒ A): A =
    awaitAssert(a, settings.assertTimeout, 100.millis)

}

object PersistenceTestKit {

  object SettingsExtension extends ExtensionId[Settings] {

    import Settings._

    override def createExtension(system: ExtendedActorSystem): Settings =
      new Settings(system.settings.config.getConfig(configPath))

  }

  class Settings(config: Config) extends Extension {

    import akka.util.Helpers._

    val assertTimeout: FiniteDuration = config.getMillisDuration("timeout")

  }

  object Settings {
    val configPath = "akka.persistence.testkit"
  }

  object ExpectedFailure extends Throwable

  object ExpectedRejection extends Throwable

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

trait UtilityAssertions {

  import scala.concurrent.duration._

  protected def now: FiniteDuration = System.nanoTime.nanos

  protected def awaitAssert[A](a: ⇒ A, max: FiniteDuration, interval: Duration = 100.millis): A = {
    val stop = now + max

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

    poll(max min interval)
  }

}
