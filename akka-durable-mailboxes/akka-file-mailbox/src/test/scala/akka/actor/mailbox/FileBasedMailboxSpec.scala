package akka.actor.mailbox

import org.apache.commons.io.FileUtils

object FileBasedMailboxSpec {
  val config = """
    File-dispatcher {
      mailbox-type = akka.actor.mailbox.FileBasedMailboxType
      throughput = 1
    }
    """
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class FileBasedMailboxSpec extends DurableMailboxSpec("File", FileBasedMailboxSpec.config) {

  def clean {
    val queuePath = FileBasedMailboxExtension(system).QueuePath
    FileUtils.deleteDirectory(new java.io.File(queuePath))
  }

  override def atStartup() {
    clean
    super.atStartup()
  }

  override def atTermination() {
    clean
    super.atTermination()
  }
}
