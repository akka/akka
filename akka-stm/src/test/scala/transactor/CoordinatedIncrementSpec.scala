package akka.transactor.test

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers

import akka.transactor.Coordinated
import akka.actor.{Actor, ActorRef}
import akka.stm._
import akka.util.duration._

import java.util.concurrent.CountDownLatch

object CoordinatedIncrement {
  case class Increment(friends: Seq[ActorRef], latch: CountDownLatch)
  case object GetCount

  class Counter(name: String) extends Actor {
    val count = Ref(0)

    implicit val txFactory = TransactionFactory(timeout = 3 seconds)

    def increment = {
      log.info(name + ": incrementing")
      count alter (_ + 1)
    }

    def receive = {
      case coordinated @ Coordinated(Increment(friends, latch)) => {
        if (friends.nonEmpty) {
          friends.head ! coordinated(Increment(friends.tail, latch))
        }
        coordinated atomic {
          increment
          deferred { latch.countDown }
          compensating { latch.countDown }
        }
      }

      case GetCount => self.reply(count.get)
    }
  }

  class Failer extends Actor {
    def receive = {
      case Coordinated(Increment(friends, latch)) => {
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
      val incrementLatch = new CountDownLatch(numCounters)
      counters(0) ! Coordinated(Increment(counters.tail, incrementLatch))
      incrementLatch.await(timeout.length, timeout.unit)
      for (counter <- counters) {
        (counter !! GetCount).get must be === 1
      }
      counters foreach (_.stop)
      failer.stop
    }

    "increment no counters with a failing transaction" in {
      val (counters, failer) = createActors
      val failLatch = new CountDownLatch(numCounters)
      counters(0) ! Coordinated(Increment(counters.tail :+ failer, failLatch))
      failLatch.await(timeout.length, timeout.unit)
      for (counter <- counters) {
        (counter !! GetCount).get must be === 0
      }
      counters foreach (_.stop)
      failer.stop
    }
  }
}
