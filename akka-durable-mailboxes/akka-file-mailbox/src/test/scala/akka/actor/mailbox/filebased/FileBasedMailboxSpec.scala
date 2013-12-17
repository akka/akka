package akka.actor.mailbox.filebased

import language.postfixOps

import akka.actor.mailbox._
import scala.concurrent.duration._
import org.apache.commons.io.FileUtils
import akka.dispatch.Mailbox

object FileBasedMailboxSpec {
  val config = """
    File-dispatcher {
      mailbox-type = akka.actor.mailbox.filebased.FileBasedMailboxType
      throughput = 1
      file-based.directory-path = "file-based"
      file-based.circuit-breaker.max-failures = 5
      file-based.circuit-breaker.call-timeout = 5 seconds
    }
               """
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class FileBasedMailboxSpec extends DurableMailboxSpec("File", FileBasedMailboxSpec.config) {

  val settings = new FileBasedMailboxSettings(system.settings, system.settings.config.getConfig("File-dispatcher"))

  "FileBasedMailboxSettings" must {
    "read the file-based section" in {
      settings.QueuePath should be("file-based")
      settings.CircuitBreakerMaxFailures should be(5)
      settings.CircuitBreakerCallTimeout should be(5 seconds)
    }
  }

  private[akka] def isDurableMailbox(m: Mailbox): Boolean = m.messageQueue.isInstanceOf[FileBasedMessageQueue]

  def clean(): Unit = FileUtils.deleteDirectory(new java.io.File(settings.QueuePath))

  override def atStartup() {
    clean()
    super.atStartup()
  }

  override def afterTermination() {
    clean()
    super.afterTermination()
  }
}
