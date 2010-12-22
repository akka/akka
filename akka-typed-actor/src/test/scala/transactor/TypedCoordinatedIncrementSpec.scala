package akka.transactor.test

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers

import akka.actor.TypedActor
import akka.stm.Ref
import akka.transactor.annotation.Coordinated
import akka.transactor.CoordinateException
import akka.transactor.Coordination._

object TypedCoordinatedIncrement {
  trait Counter {
    @Coordinated def increment: Unit
    @Coordinated def incrementAndGet: Int
    def get: Int
  }

  class CounterImpl extends TypedActor with Counter {
    val ref = Ref(0)
    def increment = ref alter (_ + 1)
    def incrementAndGet = { ref alter (_ + 1); ref.get }
    def get = ref.get
  }

  class FailerImpl extends TypedActor with Counter {
    val ref = Ref(0)
    def increment = throw new RuntimeException("Expected failure")
    def incrementAndGet = throw new RuntimeException("Expected failure")
    def get = ref.get
  }
}

class TypedCoordinatedIncrementSpec extends WordSpec with MustMatchers {
  import TypedCoordinatedIncrement._

  val numCounters = 5

  def createActors = {
    def createCounter(i: Int) = TypedActor.newInstance(classOf[Counter], classOf[CounterImpl])
    val counters = (1 to numCounters) map createCounter
    val failer = TypedActor.newInstance(classOf[Counter], classOf[FailerImpl])
    (counters, failer)
  }

  "Coordinated typed actor increment" should {
    "increment all counters by one with successful transactions" in {
      val (counters, failer) = createActors
      coordinate {
        counters foreach (_.increment)
      }
      for (counter <- counters) {
        counter.get must be === 1
      }
      counters foreach (TypedActor.stop)
      TypedActor.stop(failer)
    }

    "increment no counters with a failing transaction" in {
      val (counters, failer) = createActors
      try {
        coordinate {
          counters foreach (_.increment)
          failer.increment
        }
      } catch {
        case _ => ()
      }
      for (counter <- counters) {
        counter.get must be === 0
      }
      counters foreach (TypedActor.stop)
      TypedActor.stop(failer)
    }

    "fail when used with non-void methods" in {
      val counter = TypedActor.newInstance(classOf[Counter], classOf[CounterImpl])
      evaluating { counter.incrementAndGet } must produce [CoordinateException]
      TypedActor.stop(counter)
    }
  }
}
