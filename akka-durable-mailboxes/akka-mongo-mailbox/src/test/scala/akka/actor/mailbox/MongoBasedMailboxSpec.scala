package akka.actor.mailbox

import java.util.concurrent.TimeUnit
import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.{ BeforeAndAfterEach, BeforeAndAfterAll }
import akka.actor._
import akka.actor.Actor._
import java.util.concurrent.CountDownLatch
import akka.dispatch.MessageDispatcher

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

/*object DurableMongoMailboxSpecActorFactory {

  class MongoMailboxTestActor extends Actor {
    def receive = {
      case "sum" => reply("sum")
    }
  }

  def createMongoMailboxTestActor(id: String)(implicit dispatcher: MessageDispatcher): ActorRef = {
    val queueActor = actorOf(Props[MongoMailboxTestActor]
    queueActor.dispatcher = dispatcher
    queueActor
  }
}*/

/*class MongoBasedMailboxSpec extends WordSpec with MustMatchers with BeforeAndAfterEach with BeforeAndAfterAll {
  import DurableMongoMailboxSpecActorFactory._

  implicit val dispatcher = DurableDispatcher("mongodb", MongoNaiveDurableMailboxStorage, 1)

  "A MongoDB based naive mailbox backed actor" should {
    "should handle reply to ! for 1 message" in {
      val latch = new CountDownLatch(1)
      val queueActor = createMongoMailboxTestActor("mongoDB Backend should handle Reply to !")
      val sender = actorOf(Props(new Actor { def receive = { case "sum" => latch.countDown } })

      queueActor.!("sum")(Some(sender))
      latch.await(10, TimeUnit.SECONDS) must be (true)
    }

    "should handle reply to ! for multiple messages" in {
      val latch = new CountDownLatch(5)
      val queueActor = createMongoMailboxTestActor("mongoDB Backend should handle reply to !")
      val sender = actorOf( new Actor { def receive = { case "sum" => latch.countDown } } )

      queueActor.!("sum")(Some(sender))
      queueActor.!("sum")(Some(sender))
      queueActor.!("sum")(Some(sender))
      queueActor.!("sum")(Some(sender))
      queueActor.!("sum")(Some(sender))
      latch.await(10, TimeUnit.SECONDS) must be (true)
    }
  }

  override def beforeEach() {
    registry.local.shutdownAll
  }
}*/
