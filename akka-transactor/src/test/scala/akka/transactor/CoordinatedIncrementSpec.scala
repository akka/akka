/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.transactor

import org.scalatest.BeforeAndAfterAll

import akka.actor._
import akka.dispatch.Await
import akka.util.duration._
import akka.util.Timeout
import akka.testkit._
import scala.concurrent.stm._

object CoordinatedIncrement {

  val config = """
    akka {
      actor {
        default-dispatcher {
          core-pool-size-min = 5
          core-pool-size-max = 16
        }
      }
    }
  """

  case class Increment(friends: Seq[ActorRef])
  case object GetCount

  class Counter(name: String) extends Actor {
    val count = Ref(0)

    def receive = {
      case coordinated @ Coordinated(Increment(friends)) ⇒ {
        if (friends.nonEmpty) {
          friends.head ! coordinated(Increment(friends.tail))
        }
        coordinated.atomic { implicit t ⇒
          count transform (_ + 1)
        }
      }

      case GetCount ⇒ sender ! count.single.get
    }
  }

  class ExpectedFailureException extends RuntimeException("Expected failure")

  class Failer extends Actor {

    def receive = {
      case coordinated @ Coordinated(Increment(friends)) ⇒ {
        coordinated.atomic { t ⇒
          throw new ExpectedFailureException
        }
      }
    }
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class CoordinatedIncrementSpec extends AkkaSpec(CoordinatedIncrement.config) with BeforeAndAfterAll {
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
      counters foreach (system.stop(_))
      system.stop(failer)
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
        counters foreach (system.stop(_))
        system.stop(failer)
      }
    }
  }
}
