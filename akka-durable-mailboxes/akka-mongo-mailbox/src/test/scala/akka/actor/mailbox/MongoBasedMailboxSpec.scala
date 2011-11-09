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
  import com.mongodb.async._

  val mongo = MongoConnection("localhost", 27017)("akka")

  mongo.dropDatabase() { success ⇒ }

}
