package akka.actor.mailbox

import java.util.concurrent.TimeUnit

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.{ BeforeAndAfterEach, BeforeAndAfterAll }

import akka.actor._
import akka.actor.Actor._
import java.util.concurrent.CountDownLatch
import akka.config.Supervision.Temporary
import akka.dispatch.MessageDispatcher

class MongoBasedMailboxSpec extends DurableMailboxSpec("mongodb", MongoNaiveDurableMailboxStorage) {
  import org.apache.log4j.{ Logger, Level }
  import com.mongodb.async._

  val mongo = MongoConnection("localhost", 27017)("akka")

  mongo.dropDatabase() { success ⇒ }

  Logger.getRootLogger.setLevel(Level.DEBUG)
}

/*object DurableMongoMailboxSpecActorFactory {

  class MongoMailboxTestActor extends Actor {
    self.lifeCycle = Temporary
    def receive = {
      case "sum" => self.reply("sum")
    }
  }

  def createMongoMailboxTestActor(id: String)(implicit dispatcher: MessageDispatcher): ActorRef = {
    val queueActor = localActorOf[MongoMailboxTestActor]
    queueActor.dispatcher = dispatcher
    queueActor.start
  }
}*/

/*class MongoBasedMailboxSpec extends WordSpec with MustMatchers with BeforeAndAfterEach with BeforeAndAfterAll {
  import DurableMongoMailboxSpecActorFactory._
  
  implicit val dispatcher = DurableDispatcher("mongodb", MongoNaiveDurableMailboxStorage, 1)

  "A MongoDB based naive mailbox backed actor" should {
    "should handle reply to ! for 1 message" in {
      val latch = new CountDownLatch(1)
      val queueActor = createMongoMailboxTestActor("mongoDB Backend should handle Reply to !")
      val sender = localActorOf(new Actor { def receive = { case "sum" => latch.countDown } }).start

      queueActor.!("sum")(Some(sender))
      latch.await(10, TimeUnit.SECONDS) must be (true)
    }

    "should handle reply to ! for multiple messages" in {
      val latch = new CountDownLatch(5)
      val queueActor = createMongoMailboxTestActor("mongoDB Backend should handle reply to !")
      val sender = localActorOf( new Actor { def receive = { case "sum" => latch.countDown } } ).start

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
