package akka.remote.transport

import akka.testkit.{ DefaultTimeout, AkkaSpec }
import akka.remote.transport.TestTransport.SwitchableLoggedBehavior
import scala.concurrent.{ Await, Promise }
import scala.util.{ Success, Failure }
import akka.AkkaException

object SwitchableLoggedBehaviorSpec {
  object TestException extends AkkaException("Test exception")
}

class SwitchableLoggedBehaviorSpec extends AkkaSpec with DefaultTimeout {
  import akka.remote.transport.SwitchableLoggedBehaviorSpec._

  private def defaultBehavior = new SwitchableLoggedBehavior[Unit, Int]((unit) ⇒ Promise.successful(3).future, (unit) ⇒ ())

  "A SwitchableLoggedBehavior" must {

    "execute default behavior" in {
      val behavior = defaultBehavior

      Await.result(behavior(), timeout.duration) == 3 must be(true)
    }

    "be able to push generic behavior" in {
      val behavior = defaultBehavior

      behavior.push((unit) ⇒ Promise.successful(4).future)
      Await.result(behavior(), timeout.duration) == 4 must be(true)

      behavior.push((unit) ⇒ Promise.failed(TestException).future)
      behavior().value match {
        case Some(Failure(e)) if e eq TestException ⇒
        case _                                      ⇒ fail("Expected exception")
      }
    }

    "be able to push constant behavior" in {
      val behavior = defaultBehavior
      behavior.pushConstant(5)

      Await.result(behavior(), timeout.duration) == 3 must be(false)
      Await.result(behavior(), timeout.duration) == 5 must be(true)
    }

    "be able to push failure behavior" in {
      val behavior = defaultBehavior
      behavior.pushError(TestException)

      behavior().value match {
        case Some(Failure(e)) if e eq TestException ⇒
        case _                                      ⇒ fail("Expected exception")
      }
    }

    "be able to push and pop behavior" in {
      val behavior = defaultBehavior

      behavior.pushConstant(5)
      Await.result(behavior(), timeout.duration) == 5 must be(true)

      behavior.pushConstant(7)
      Await.result(behavior(), timeout.duration) == 7 must be(true)

      behavior.pop()
      Await.result(behavior(), timeout.duration) == 5 must be(true)

      behavior.pop()
      Await.result(behavior(), timeout.duration) == 3 must be(true)

    }

    "protect the default behavior from popped out" in {
      val behavior = defaultBehavior
      behavior.pop()
      behavior.pop()
      behavior.pop()

      Await.result(behavior(), timeout.duration) == 3 must be(true)
    }

    "enable delayed completition" in {
      val behavior = defaultBehavior
      val controlPromise = behavior.pushDelayed
      val f = behavior()

      f.isCompleted must be(false)
      controlPromise.success(())

      awaitCond(f.isCompleted)
    }

    "log calls and parametrers" in {
      val logPromise = Promise[Int]()
      val behavior = new SwitchableLoggedBehavior[Int, Int]((i) ⇒ Promise.successful(3).future, (i) ⇒ logPromise.success(i))

      behavior(11)
      Await.result(logPromise.future, timeout.duration) == 11 must be(true)
    }

  }

}
