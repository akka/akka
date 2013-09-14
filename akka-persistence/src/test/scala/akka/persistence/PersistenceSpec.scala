package akka.persistence

import java.io.File
import java.util.concurrent.atomic.AtomicInteger

import org.apache.commons.io.FileUtils
import org.scalatest.BeforeAndAfterEach

import akka.actor.ActorRef
import akka.testkit.AkkaSpec
import akka.testkit.TestActor.Watch

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

  protected def stopAndAwaitTermination(ref: ActorRef) {
    testActor ! Watch(ref)
    system.stop(ref)
    expectTerminated(ref)
  }

  override protected def beforeEach() {
    _name = namePrefix + counter.incrementAndGet()
  }

  override protected def afterTermination() {
    FileUtils.deleteDirectory(new File(system.settings.config.getString("akka.persistence.journal.leveldb.dir")))
  }
}

trait TurnOffRecoverOnStart { this: Processor ⇒
  override def preStartProcessor(): Unit = ()
}
