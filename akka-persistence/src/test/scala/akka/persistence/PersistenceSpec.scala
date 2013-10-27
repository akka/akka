/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import java.io.File
import java.util.concurrent.atomic.AtomicInteger

import scala.reflect.ClassTag
import scala.util.control.NoStackTrace

import com.typesafe.config.ConfigFactory

import org.apache.commons.io.FileUtils
import org.scalatest.BeforeAndAfterEach

import akka.actor.Props
import akka.testkit.AkkaSpec

trait PersistenceSpec extends BeforeAndAfterEach { this: AkkaSpec ⇒
  private var _name: String = _

  val extension = Persistence(system)
  val counter = new AtomicInteger(0)

  /**
   * Unique name per test.
   */
  def name = _name

  /**
   * Prefix for generating a unique name per test.
   */
  def namePrefix: String = "processor"

  /**
   * Creates a processor with current name as constructor argument.
   */
  def namedProcessor[T <: NamedProcessor: ClassTag] =
    system.actorOf(Props(implicitly[ClassTag[T]].runtimeClass, name))

  override protected def beforeEach() {
    _name = namePrefix + counter.incrementAndGet()
  }

  override protected def afterTermination() {
    List("akka.persistence.journal.leveldb.dir", "akka.persistence.snapshot-store.local.dir") foreach { s ⇒
      FileUtils.deleteDirectory(new File(system.settings.config.getString(s)))
    }
  }
}

object PersistenceSpec {
  def config(plugin: String, test: String) = ConfigFactory.parseString(
    s"""
      serialize-creators = on
      serialize-messages = on
      akka.persistence.journal.plugin = "akka.persistence.journal.${plugin}"
      akka.persistence.journal.leveldb.dir = "target/journal-${test}-spec"
      akka.persistence.snapshot-store.local.dir = "target/snapshots-${test}-spec/"
    """)
}

abstract class NamedProcessor(name: String) extends Processor {
  override def processorId: String = name
}

trait TurnOffRecoverOnStart { this: Processor ⇒
  override def preStart(): Unit = ()
}

class TestException(msg: String) extends Exception(msg) with NoStackTrace

case object GetState
