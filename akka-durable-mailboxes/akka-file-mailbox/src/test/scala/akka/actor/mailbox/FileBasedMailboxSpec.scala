package akka.actor.mailbox

import org.apache.commons.io.FileUtils
import akka.dispatch.CustomMailboxType

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class FileBasedMailboxSpec extends DurableMailboxSpec("File",
  new CustomMailboxType("akka.actor.mailbox.FileBasedMailbox")) {

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
