package akka.transactor

import org.scalatest.BeforeAndAfterAll

import akka.actor.ActorSystem
import akka.actor._
import akka.stm.{ Ref, TransactionFactory }
import akka.util.duration._
import akka.testkit._
import akka.dispatch.Await

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

      case GetCount ⇒ sender ! count.get
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

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class CoordinatedIncrementSpec extends AkkaSpec with BeforeAndAfterAll {
  import CoordinatedIncrement._

  implicit val timeout = Timeout(5.seconds.dilated)

  val numCounters = 4

  def actorOfs = {
    def createCounter(i: Int) = system.actorOf(Props(new Counter("counter" + i)))
    val counters = (1 to numCounters) map createCounter
    val failer = system.actorOf(Props(new Failer))
    (counters, failer)
  }

  "Coordinated increment" should {
    "increment all counters by one with successful transactions" in {
      val (counters, failer) = actorOfs
      val coordinated = Coordinated()
      counters(0) ! coordinated(Increment(counters.tail))
      coordinated.await
      for (counter ← counters) {
        Await.result((counter ? GetCount).mapTo[Int], timeout.duration) must be === 1
      }
      counters foreach (_.stop())
      failer.stop()
    }

    "increment no counters with a failing transaction" in {
      val ignoreExceptions = Seq(
        EventFilter[ExpectedFailureException](),
        EventFilter[CoordinatedTransactionException](),
        EventFilter[ActorTimeoutException]())
      filterEvents(ignoreExceptions) {
        val (counters, failer) = actorOfs
        val coordinated = Coordinated()
        counters(0) ! Coordinated(Increment(counters.tail :+ failer))
        coordinated.await
        for (counter ← counters) {
          Await.result(counter ? GetCount, timeout.duration) must be === 0
        }
        counters foreach (_.stop())
        failer.stop()
      }
    }
  }
}
