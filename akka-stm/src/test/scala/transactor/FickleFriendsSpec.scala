package akka.transactor.test

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.BeforeAndAfterAll

import akka.transactor.Coordinated
import akka.actor.{ Actor, ActorRef, ActorTimeoutException }
import akka.stm._
import akka.util.duration._
import akka.event.EventHandler
import akka.transactor.CoordinatedTransactionException
import akka.testkit._

import scala.util.Random.{ nextInt ⇒ random }

import java.util.concurrent.CountDownLatch

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

      case GetCount ⇒ self.reply(count.get)
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

      case GetCount ⇒ self.reply(count.get)
    }
  }
}

class FickleFriendsSpec extends WordSpec with MustMatchers with BeforeAndAfterAll {
  import FickleFriends._

  val numCounters = 2

  def createActors = {
    def createCounter(i: Int) = Actor.actorOf(new FickleCounter("counter" + i))
    val counters = (1 to numCounters) map createCounter
    val coordinator = Actor.actorOf(new Coordinator("coordinator"))
    (counters, coordinator)
  }

  "Coordinated fickle friends" should {
    "eventually succeed to increment all counters by one" in {
      val ignoreExceptions = Seq(
        EventFilter[ExpectedFailureException],
        EventFilter[CoordinatedTransactionException],
        EventFilter[ActorTimeoutException])
      EventHandler.notify(TestEvent.Mute(ignoreExceptions))
      val (counters, coordinator) = createActors
      val latch = new CountDownLatch(1)
      coordinator ! FriendlyIncrement(counters, latch)
      latch.await // this could take a while
      (coordinator ? GetCount).as[Int].get must be === 1
      for (counter ← counters) {
        (counter ? GetCount).as[Int].get must be === 1
      }
      counters foreach (_.stop())
      coordinator.stop()
      EventHandler.notify(TestEvent.UnMute(ignoreExceptions))
    }
  }
}
