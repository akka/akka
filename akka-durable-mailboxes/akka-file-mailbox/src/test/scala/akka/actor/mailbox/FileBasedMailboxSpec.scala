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
    }
    """
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class FileBasedMailboxSpec extends DurableMailboxSpec("File", FileBasedMailboxSpec.config) {

  val queuePath = new FileBasedMailboxSettings(system.settings, system.settings.config.getConfig("File-dispatcher")).QueuePath

  "FileBasedMailboxSettings" must {
    "read the file-based section" in {
      queuePath must be("file-based")
    }
  }

  def clean() {
    FileUtils.deleteDirectory(new java.io.File(queuePath))
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
