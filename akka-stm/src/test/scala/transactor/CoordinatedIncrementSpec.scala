package akka.transactor.test

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.BeforeAndAfterAll

import akka.transactor.Coordinated
import akka.actor.{ Actor, ActorRef, ActorTimeoutException }
import akka.stm.{ Ref, TransactionFactory }
import akka.util.duration._
import akka.event.EventHandler
import akka.transactor.CoordinatedTransactionException
import akka.testkit._

object CoordinatedIncrement {
  case class Increment(friends: Seq[ActorRef])
  case object GetCount

  class Counter(name: String) extends Actor {
    val count = Ref(0)

    implicit val txFactory = TransactionFactory(timeout = 3 seconds)

    def increment = {
      count alter (_ + 1)
    }

    def receive = {
      case coordinated @ Coordinated(Increment(friends)) ⇒ {
        if (friends.nonEmpty) {
          friends.head ! coordinated(Increment(friends.tail))
        }
        coordinated atomic {
          increment
        }
      }

      case GetCount ⇒ reply(count.get)
    }
  }

  class ExpectedFailureException extends RuntimeException("Expected failure")

  class Failer extends Actor {
    val txFactory = TransactionFactory(timeout = 3 seconds)

    def receive = {
      case coordinated @ Coordinated(Increment(friends)) ⇒ {
        coordinated.atomic(txFactory) {
          throw new ExpectedFailureException
        }
      }
    }
  }
}

class CoordinatedIncrementSpec extends WordSpec with MustMatchers with BeforeAndAfterAll {
  import CoordinatedIncrement._

  val numCounters = 5
  val timeout = 5 seconds

  def createActors = {
    def createCounter(i: Int) = Actor.actorOf(new Counter("counter" + i))
    val counters = (1 to numCounters) map createCounter
    val failer = Actor.actorOf(new Failer)
    (counters, failer)
  }

  "Coordinated increment" should {
    "increment all counters by one with successful transactions" in {
      val (counters, failer) = createActors
      val coordinated = Coordinated()
      counters(0) ! coordinated(Increment(counters.tail))
      coordinated.await
      for (counter ← counters) {
        (counter ? GetCount).as[Int].get must be === 1
      }
      counters foreach (_.stop())
      failer.stop()
    }

    "increment no counters with a failing transaction" in {
      val ignoreExceptions = Seq(
        EventFilter[ExpectedFailureException],
        EventFilter[CoordinatedTransactionException],
        EventFilter[ActorTimeoutException])
      EventHandler.notify(TestEvent.Mute(ignoreExceptions))
      val (counters, failer) = createActors
      val coordinated = Coordinated()
      counters(0) ! Coordinated(Increment(counters.tail :+ failer))
      coordinated.await
      for (counter ← counters) {
        (counter ? GetCount).as[Int].get must be === 0
      }
      counters foreach (_.stop())
      failer.stop()
      EventHandler.notify(TestEvent.UnMute(ignoreExceptions))
    }
  }
}
