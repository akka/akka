package akka.actor.mailbox

import org.apache.commons.io.FileUtils

class FileBasedMailboxSpec extends DurableMailboxSpec("File", FileDurableMailboxStorage) {

  def clean {
    import FileBasedMailboxUtil._
    FileUtils.deleteDirectory(new java.io.File(queuePath))
  }

  override def beforeAll() {
    clean
    super.beforeAll
  }

  override def afterEach() {
    clean
    super.afterEach
  }
}
