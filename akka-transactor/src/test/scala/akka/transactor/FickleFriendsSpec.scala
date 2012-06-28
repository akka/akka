/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.transactor

import language.postfixOps

import org.scalatest.BeforeAndAfterAll

import akka.actor._
import akka.dispatch.Await
import akka.util.duration._
import akka.testkit._
import akka.testkit.TestEvent.Mute
import scala.concurrent.stm._
import scala.util.Random.{ nextInt ⇒ random }
import java.util.concurrent.CountDownLatch
import akka.pattern.{ AskTimeoutException, ask }
import akka.util.{ NonFatal, Timeout }

object FickleFriends {
  case class FriendlyIncrement(friends: Seq[ActorRef], timeout: Timeout, latch: CountDownLatch)
  case class Increment(friends: Seq[ActorRef])
  case object GetCount

  /**
   * Coordinator will keep trying to coordinate an increment until successful.
   */
  class Coordinator(name: String) extends Actor {
    val count = Ref(0)

    def increment(implicit txn: InTxn) = {
      count transform (_ + 1)
    }

    def receive = {
      case FriendlyIncrement(friends, timeout, latch) ⇒ {
        var success = false
        while (!success) {
          try {
            val coordinated = Coordinated()(timeout)
            if (friends.nonEmpty) {
              friends.head ! coordinated(Increment(friends.tail))
            }
            coordinated.atomic { implicit t ⇒
              increment
              Txn.afterCommit { status ⇒
                success = true
                latch.countDown()
              }
            }
          } catch {
            case NonFatal(_) ⇒ () // swallow exceptions
          }
        }
      }

      case GetCount ⇒ sender ! count.single.get
    }
  }

  class ExpectedFailureException(message: String) extends RuntimeException(message)

  /**
   * FickleCounter randomly fails at different points with 50% chance of failing overall.
   */
  class FickleCounter(name: String) extends Actor {
    val count = Ref(0)

    val maxFailures = 3
    var failures = 0

    def increment(implicit txn: InTxn) = {
      count transform (_ + 1)
    }

    def failIf(x: Int, y: Int) = {
      if (x == y && failures < maxFailures) {
        failures += 1
        throw new ExpectedFailureException("Random fail at position " + x)
      }
    }

    def receive = {
      case coordinated @ Coordinated(Increment(friends)) ⇒ {
        val failAt = random(8)
        failIf(failAt, 0)
        if (friends.nonEmpty) {
          friends.head ! coordinated(Increment(friends.tail))
        }
        failIf(failAt, 1)
        coordinated.atomic { implicit t ⇒
          failIf(failAt, 2)
          increment
          failIf(failAt, 3)
        }
      }

      case GetCount ⇒ sender ! count.single.get
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
        EventFilter[AskTimeoutException]())
      system.eventStream.publish(Mute(ignoreExceptions))
      val (counters, coordinator) = actorOfs
      val latch = new CountDownLatch(1)
      coordinator ! FriendlyIncrement(counters, timeout, latch)
      latch.await // this could take a while
      Await.result(coordinator ? GetCount, timeout.duration) must be === 1
      for (counter ← counters) {
        Await.result(counter ? GetCount, timeout.duration) must be === 1
      }
      counters foreach (system.stop(_))
      system.stop(coordinator)
    }
  }
}
