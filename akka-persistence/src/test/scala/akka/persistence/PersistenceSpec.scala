package akka.persistence

import java.io.File
import java.util.concurrent.atomic.AtomicInteger

import scala.reflect.ClassTag

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
    FileUtils.deleteDirectory(new File(system.settings.config.getString("akka.persistence.journal.leveldb.dir")))
  }
}

abstract class NamedProcessor(name: String) extends Processor {
  override def processorId: String = name
}

trait TurnOffRecoverOnStart { this: Processor ⇒
  override def preStart(): Unit = ()
}

case object GetState
