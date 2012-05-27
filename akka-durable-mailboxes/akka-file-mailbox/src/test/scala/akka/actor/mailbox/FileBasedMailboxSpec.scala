package akka.actor.mailbox

import org.apache.commons.io.FileUtils
import com.typesafe.config.ConfigFactory
import akka.dispatch.Mailbox

object FileBasedMailboxSpec {
  val config = """
    File-dispatcher {
      mailbox-type = akka.actor.mailbox.FileBasedMailboxType
      throughput = 1
      file-based.directory-path = "file-based"
      file-based.circuit-breaker.max-failures = 5
      file-based.circuit-breaker.call-timeout = 5 seconds
    }
               """
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class FileBasedMailboxSpec extends DurableMailboxSpec("File", FileBasedMailboxSpec.config) {

  val queuePath = new FileBasedMailboxSettings(system.settings, system.settings.config.getConfig("File-dispatcher")).QueuePath
  val settings = new FileBasedMailboxSettings(system.settings, system.settings.config.getConfig("File-dispatcher"))

  "FileBasedMailboxSettings" must {
    "read the file-based section" in {
      settings.QueuePath must be("file-based")
      settings.CircuitBreakerMaxFailures must be(5)

      import akka.util.duration._

      settings.CircuitBreakerCallTimeout must be(5 seconds)
    }
  }

  def clean() {
    FileUtils.deleteDirectory(new java.io.File(settings.QueuePath))
  }

  override def atStartup() {
    clean()
    super.atStartup()
  }

  override def atTermination() {
    clean()
    super.atTermination()
  }
}
