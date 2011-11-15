package akka.actor.mailbox

import org.apache.commons.io.FileUtils

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class FileBasedMailboxSpec extends DurableMailboxSpec("File", FileDurableMailboxType) {

  def clean {
    val queuePath = FileBasedMailbox.queuePath(app.config)
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
