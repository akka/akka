package akka.transactor.test

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers

import akka.transactor.Coordinated
import akka.actor.{Actor, ActorRef}
import akka.stm.{Ref, TransactionFactory}
import akka.util.duration._

object CoordinatedIncrement {
  case class Increment(friends: Seq[ActorRef])
  case object GetCount

  class Counter(name: String) extends Actor {
    val count = Ref(0)

    implicit val txFactory = TransactionFactory(timeout = 3 seconds)

    def increment = {
      log.slf4j.info(name + ": incrementing")
      count alter (_ + 1)
    }

    def receive = {
      case coordinated @ Coordinated(Increment(friends)) => {
        if (friends.nonEmpty) {
          friends.head ! coordinated(Increment(friends.tail))
        }
        coordinated atomic {
          increment
        }
      }

      case GetCount => self.reply(count.get)
    }
  }

  class Failer extends Actor {
    def receive = {
      case Coordinated(Increment(friends)) => {
        throw new RuntimeException("Expected failure")
      }
    }
  }
}

class CoordinatedIncrementSpec extends WordSpec with MustMatchers {
  import CoordinatedIncrement._

  val numCounters = 5
  val timeout = 5 seconds

  def createActors = {
    def createCounter(i: Int) = Actor.actorOf(new Counter("counter" + i)).start
    val counters = (1 to numCounters) map createCounter
    val failer = Actor.actorOf(new Failer).start
    (counters, failer)
  }

  "Coordinated increment" should {
    "increment all counters by one with successful transactions" in {
      val (counters, failer) = createActors
      val coordinated = Coordinated()
      counters(0) ! coordinated(Increment(counters.tail))
      coordinated.await
      for (counter <- counters) {
        (counter !! GetCount).get must be === 1
      }
      counters foreach (_.stop)
      failer.stop
    }

    "increment no counters with a failing transaction" in {
      val (counters, failer) = createActors
      val coordinated = Coordinated()
      counters(0) ! Coordinated(Increment(counters.tail :+ failer))
      coordinated.await
      for (counter <- counters) {
        (counter !! GetCount).get must be === 0
      }
      counters foreach (_.stop)
      failer.stop
    }
  }
}
