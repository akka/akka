package akka.transactor

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.BeforeAndAfterAll
import akka.actor.ActorSystem
import akka.actor._
import akka.stm._
import akka.util.duration._
import akka.testkit._
import scala.util.Random.{ nextInt ⇒ random }
import java.util.concurrent.CountDownLatch
import akka.testkit.TestEvent.Mute

object FickleFriends {
  case class FriendlyIncrement(friends: Seq[ActorRef], latch: CountDownLatch)
  case class Increment(friends: Seq[ActorRef])
  case object GetCount

  /**
   * Coordinator will keep trying to coordinate an increment until successful.
   */
  class Coordinator(name: String) extends Actor {
    val count = Ref(0)

    implicit val txFactory = TransactionFactory(timeout = 3 seconds)

    def increment = {
      count alter (_ + 1)
    }

    def receive = {
      case FriendlyIncrement(friends, latch) ⇒ {
        var success = false
        while (!success) {
          try {
            val coordinated = Coordinated()
            if (friends.nonEmpty) {
              friends.head ! coordinated(Increment(friends.tail))
            }
            coordinated atomic {
              increment
              deferred {
                success = true
                latch.countDown()
              }
            }
          } catch {
            case _ ⇒ () // swallow exceptions
          }
        }
      }

      case GetCount ⇒ sender ! count.get
    }
  }

  class ExpectedFailureException(message: String) extends RuntimeException(message)

  /**
   * FickleCounter randomly fails at different points with 50% chance of failing overall.
   */
  class FickleCounter(name: String) extends Actor {
    val count = Ref(0)

    implicit val txFactory = TransactionFactory(timeout = 3 seconds)

    def increment = {
      count alter (_ + 1)
    }

    def failIf(x: Int, y: Int) = {
      if (x == y) throw new ExpectedFailureException("Random fail at position " + x)
    }

    def receive = {
      case coordinated @ Coordinated(Increment(friends)) ⇒ {
        val failAt = random(8)
        failIf(failAt, 0)
        if (friends.nonEmpty) {
          friends.head ! coordinated(Increment(friends.tail))
        }
        failIf(failAt, 1)
        coordinated atomic {
          failIf(failAt, 2)
          increment
          failIf(failAt, 3)
        }
      }

      case GetCount ⇒ sender ! count.get
    }
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class FickleFriendsSpec extends AkkaSpec with BeforeAndAfterAll {
  import FickleFriends._

  implicit val timeout = Timeout(5.seconds.dilated)

  val numCounters = 2

  def actorOfs = {
    def createCounter(i: Int) = system.actorOf(Props(new FickleCounter("counter" + i)))
    val counters = (1 to numCounters) map createCounter
    val coordinator = system.actorOf(Props(new Coordinator("coordinator")))
    (counters, coordinator)
  }

  "Coordinated fickle friends" should {
    "eventually succeed to increment all counters by one" in {
      val ignoreExceptions = Seq(
        EventFilter[ExpectedFailureException](),
        EventFilter[CoordinatedTransactionException](),
        EventFilter[ActorTimeoutException]())
      system.eventStream.publish(Mute(ignoreExceptions))
      val (counters, coordinator) = actorOfs
      val latch = new CountDownLatch(1)
      coordinator ! FriendlyIncrement(counters, latch)
      latch.await // this could take a while
      (coordinator ? GetCount).as[Int].get must be === 1
      for (counter ← counters) {
        (counter ? GetCount).as[Int].get must be === 1
      }
      counters foreach (_.stop())
      coordinator.stop()
    }
  }
}
