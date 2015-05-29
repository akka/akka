/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import java.io.File
import java.util.concurrent.atomic.AtomicInteger

import scala.reflect.ClassTag
import scala.util.control.NoStackTrace

import com.typesafe.config.{ Config, ConfigFactory }

import org.apache.commons.io.FileUtils
import org.scalatest.BeforeAndAfterEach

import akka.actor.Props
import akka.testkit.AkkaSpec

abstract class PersistenceSpec(config: Config) extends AkkaSpec(config) with BeforeAndAfterEach with Cleanup { this: AkkaSpec ⇒
  private var _name: String = _

  lazy val extension = Persistence(system)
  val counter = new AtomicInteger(0)

  /**
   * Unique name per test.
   */
  def name = _name

  /**
   * Prefix for generating a unique name per test.
   */
  def namePrefix: String = system.name

  /**
   * Creates a persistent actor with current name as constructor argument.
   */
  def namedPersistentActor[T <: NamedPersistentActor: ClassTag] =
    system.actorOf(Props(implicitly[ClassTag[T]].runtimeClass, name))

  override protected def beforeEach() {
    _name = s"${namePrefix}-${counter.incrementAndGet()}"
  }
}

object PersistenceSpec {
  def config(plugin: String, test: String, serialization: String = "on", extraConfig: Option[String] = None) =
    extraConfig.map(ConfigFactory.parseString(_)).getOrElse(ConfigFactory.empty()).withFallback(
      ConfigFactory.parseString(
        s"""
      akka.actor.serialize-creators = ${serialization}
      akka.actor.serialize-messages = ${serialization}
      akka.persistence.publish-plugin-commands = on
      akka.persistence.journal.plugin = "akka.persistence.journal.${plugin}"
      akka.persistence.journal.leveldb.dir = "target/journal-${test}"
      akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
      akka.persistence.snapshot-store.local.dir = "target/snapshots-${test}/"
      akka.test.single-expect-default = 10s
    """))
}

trait Cleanup { this: AkkaSpec ⇒
  val storageLocations = List(
    "akka.persistence.journal.leveldb.dir",
    "akka.persistence.journal.leveldb-shared.store.dir",
    "akka.persistence.snapshot-store.local.dir").map(s ⇒ new File(system.settings.config.getString(s)))

  override protected def atStartup() {
    storageLocations.foreach(FileUtils.deleteDirectory)
  }

  override protected def afterTermination() {
    storageLocations.foreach(FileUtils.deleteDirectory)
  }
}

abstract class NamedPersistentActor(name: String) extends PersistentActor {
  override def persistenceId: String = name
}

trait TurnOffRecoverOnStart { this: Eventsourced ⇒
  override def preStart(): Unit = ()
}

class TestException(msg: String) extends Exception(msg) with NoStackTrace

case object GetState
