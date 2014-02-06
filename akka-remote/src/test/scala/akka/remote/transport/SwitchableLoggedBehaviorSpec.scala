package akka.remote.transport

import akka.testkit.{ DefaultTimeout, AkkaSpec }
import akka.remote.transport.TestTransport.SwitchableLoggedBehavior
import scala.concurrent.{ Await, Promise }
import scala.util.Failure
import akka.AkkaException
import scala.util.control.NoStackTrace

object SwitchableLoggedBehaviorSpec {
  object TestException extends AkkaException("Test exception") with NoStackTrace
}

class SwitchableLoggedBehaviorSpec extends AkkaSpec with DefaultTimeout {
  import akka.remote.transport.SwitchableLoggedBehaviorSpec._

  private def defaultBehavior = new SwitchableLoggedBehavior[Unit, Int]((_) ⇒ Promise.successful(3).future, (_) ⇒ ())

  "A SwitchableLoggedBehavior" must {

    "execute default behavior" in {
      val behavior = defaultBehavior

      Await.result(behavior(()), timeout.duration) should be(3)
    }

    "be able to push generic behavior" in {
      val behavior = defaultBehavior

      behavior.push((_) ⇒ Promise.successful(4).future)
      Await.result(behavior(()), timeout.duration) should be(4)

      behavior.push((_) ⇒ Promise.failed(TestException).future)
      behavior(()).value match {
        case Some(Failure(`TestException`)) ⇒
        case _                              ⇒ fail("Expected exception")
      }
    }

    "be able to push constant behavior" in {
      val behavior = defaultBehavior
      behavior.pushConstant(5)

      Await.result(behavior(()), timeout.duration) should be(5)
      Await.result(behavior(()), timeout.duration) should be(5)
    }

    "be able to push failure behavior" in {
      val behavior = defaultBehavior
      behavior.pushError(TestException)

      behavior(()).value match {
        case Some(Failure(e)) if e eq TestException ⇒
        case _                                      ⇒ fail("Expected exception")
      }
    }

    "be able to push and pop behavior" in {
      val behavior = defaultBehavior

      behavior.pushConstant(5)
      Await.result(behavior(()), timeout.duration) should be(5)

      behavior.pushConstant(7)
      Await.result(behavior(()), timeout.duration) should be(7)

      behavior.pop()
      Await.result(behavior(()), timeout.duration) should be(5)

      behavior.pop()
      Await.result(behavior(()), timeout.duration) should be(3)

    }

    "protect the default behavior from popped out" in {
      val behavior = defaultBehavior
      behavior.pop()
      behavior.pop()
      behavior.pop()

      Await.result(behavior(()), timeout.duration) should be(3)
    }

    "enable delayed completition" in {
      val behavior = defaultBehavior
      val controlPromise = behavior.pushDelayed
      val f = behavior(())

      f.isCompleted should be(false)
      controlPromise.success(())

      awaitCond(f.isCompleted)
    }

    "log calls and parametrers" in {
      val logPromise = Promise[Int]()
      val behavior = new SwitchableLoggedBehavior[Int, Int]((i) ⇒ Promise.successful(3).future, (i) ⇒ logPromise.success(i))

      behavior(11)
      Await.result(logPromise.future, timeout.duration) should be(11)
    }

  }

}
