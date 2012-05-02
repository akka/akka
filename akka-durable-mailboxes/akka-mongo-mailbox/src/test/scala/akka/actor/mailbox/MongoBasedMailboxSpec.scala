package akka.actor.mailbox

import java.util.concurrent.TimeUnit
import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.{ BeforeAndAfterEach, BeforeAndAfterAll }
import akka.actor._
import akka.actor.Actor._
import java.util.concurrent.CountDownLatch
import akka.dispatch.{ Mailbox, MessageDispatcher }

object MongoBasedMailboxSpec {
  val config = """
    mongodb-dispatcher {
      mailbox-type = akka.actor.mailbox.MongoBasedMailboxType
      throughput = 1
      mongodb.uri = "mongodb://localhost:27123/akka.mailbox"
    }
    """
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class MongoBasedMailboxSpec extends DurableMailboxSpec("mongodb", MongoBasedMailboxSpec.config) {

  import com.mongodb.async._

  lazy val mongod = new ProcessBuilder("mongod", "--dbpath", "mongoDB", "--bind_ip", "127.0.0.1", "--port", "27123").start()
  lazy val mongo = MongoConnection("localhost", 27123)("akka")

  def isDurableMailbox(m: Mailbox): Boolean = m.messageQueue.isInstanceOf[MongoBasedMessageQueue]

  override def atStartup(): Unit = {
    // start MongoDB daemon
    new java.io.File("mongoDB").mkdir()
    val in = mongod.getInputStream

    try {
      streamMustContain(in, "waiting for connections on port")
      mongo.dropDatabase() { success ⇒ }
    } catch {
      case e ⇒ mongod.destroy(); throw e
    }
  }

  override def atTermination(): Unit = mongod.destroy()
}