/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.stream

import java.io.File
import java.util.concurrent.atomic.AtomicInteger

import scala.reflect.ClassTag

import com.typesafe.config.ConfigFactory

import org.apache.commons.io.FileUtils
import org.scalatest.BeforeAndAfterEach

import akka.actor.Props
import akka.persistence._
import akka.stream.testkit.AkkaSpec

// ---------------------------------------------------------------------------
// FIXME: remove this file once going back to project dependencies
// ---------------------------------------------------------------------------

trait PersistenceSpec extends BeforeAndAfterEach with Cleanup { this: AkkaSpec ⇒
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
   * Creates a processor with current name as constructor argument.
   */
  def namedProcessor[T <: NamedProcessor: ClassTag] =
    system.actorOf(Props(implicitly[ClassTag[T]].runtimeClass, name))

  override protected def beforeEach() {
    _name = s"${namePrefix}-${counter.incrementAndGet()}"
  }
}

object PersistenceSpec {
  def config(plugin: String, test: String, serialization: String = "on") = ConfigFactory.parseString(
    s"""
      akka.actor.serialize-creators = ${serialization}
      akka.actor.serialize-messages = ${serialization}
      akka.persistence.publish-confirmations = on
      akka.persistence.publish-plugin-commands = on
      akka.persistence.journal.plugin = "akka.persistence.journal.${plugin}"
      akka.persistence.journal.leveldb.dir = "target/journal-${test}"
      akka.persistence.snapshot-store.local.dir = "target/snapshots-${test}/"
      akka.test.single-expect-default = 10s
    """)
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

abstract class NamedProcessor(name: String) extends Processor {
  override def processorId: String = name
}
