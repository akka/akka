package akka.dispatch

import java.util.concurrent._

import akka.actor._
import akka.pattern.ask
import akka.testkit.{ AkkaSpec, DefaultTimeout, TestLatch, filterException }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.junit.JUnitSuiteLike
import org.scalatest.prop.Checkers

import scala.concurrent.duration._
import scala.concurrent.{ Await, Awaitable, ExecutionContext, Future }
import scala.language.postfixOps
import scala.reflect.ClassTag
import scala.util.control.NonFatal
import scala.util.{ Failure, Success }

object FutureSpec {

  def ready[T](awaitable: Awaitable[T], atMost: Duration): awaitable.type =
    try Await.ready(awaitable, atMost) catch {
      case t: TimeoutException ⇒ throw t
      case e if NonFatal(e)    ⇒ awaitable //swallow
    }

  class TestActor extends Actor {
    def receive = {
      case "Hello" ⇒ sender() ! "World"
      case "Failure" ⇒
        sender() ! Status.Failure(new RuntimeException("Expected exception; to test fault-tolerance"))
      case "NoReply" ⇒
    }
  }

  class TestDelayActor(await: TestLatch) extends Actor {
    def receive = {
      case "Hello" ⇒
        FutureSpec.ready(await, TestLatch.DefaultTimeout); sender() ! "World"
      case "NoReply" ⇒ FutureSpec.ready(await, TestLatch.DefaultTimeout)
      case "Failure" ⇒
        FutureSpec.ready(await, TestLatch.DefaultTimeout)
        sender() ! Status.Failure(new RuntimeException("Expected exception; to test fault-tolerance"))
    }
  }
}

class JavaFutureSpec extends JavaFutureTests with JUnitSuiteLike

class FutureSpec extends AkkaSpec with Checkers with BeforeAndAfterAll with DefaultTimeout {
  import FutureSpec._
  implicit val ec: ExecutionContext = system.dispatcher

  "A Future" when {
    "from an Actor" which {
      "returns a result" must {
        behave like futureWithResult { test ⇒
          val actor = system.actorOf(Props[TestActor]())
          val future = actor ? "Hello"
          FutureSpec.ready(future, timeout.duration)
          test(future, "World")
          system.stop(actor)
        }
      }
      "throws an exception" must {
        behave like futureWithException[RuntimeException] { test ⇒
          filterException[RuntimeException] {
            val actor = system.actorOf(Props[TestActor]())
            val future = actor ? "Failure"
            FutureSpec.ready(future, timeout.duration)
            test(future, "Expected exception; to test fault-tolerance")
            system.stop(actor)
          }
        }
      }
    }

    "using flatMap with an Actor" which {
      "will return a result" must {
        behave like futureWithResult { test ⇒
          val actor1 = system.actorOf(Props[TestActor]())
          val actor2 = system.actorOf(Props(new Actor { def receive = { case s: String ⇒ sender() ! s.toUpperCase } }))
          val future = actor1 ? "Hello" flatMap { case s: String ⇒ actor2 ? s }
          FutureSpec.ready(future, timeout.duration)
          test(future, "WORLD")
          system.stop(actor1)
          system.stop(actor2)
        }
      }
      "will throw an exception" must {
        behave like futureWithException[ArithmeticException] { test ⇒
          filterException[ArithmeticException] {
            val actor1 = system.actorOf(Props[TestActor]())
            val actor2 = system.actorOf(Props(new Actor { def receive = { case s: String ⇒ sender() ! Status.Failure(new ArithmeticException("/ by zero")) } }))
            val future = actor1 ? "Hello" flatMap { case s: String ⇒ actor2 ? s }
            FutureSpec.ready(future, timeout.duration)
            test(future, "/ by zero")
            system.stop(actor1)
            system.stop(actor2)
          }
        }
      }
    }
  }

  def futureWithResult(f: ((Future[Any], Any) ⇒ Unit) ⇒ Unit) {
    "be completed" in {
      f((future, _) ⇒ future.isCompleted shouldBe true)
    }
    "contain a value" in {
      f((future, result) ⇒ future.value should ===(Some(Success(result))))
    }
  }

  def futureWithException[E <: Throwable: ClassTag](f: ((Future[Any], String) ⇒ Unit) ⇒ Unit) {
    "be completed" in {
      f((future, _) ⇒ future.isCompleted shouldBe true)
    }
    "contain a value" in {
      f((future, message) ⇒ {
        future.value.isDefined shouldBe true
        future.value.get shouldBe a[Failure[_]]
        val Failure(f) = future.value.get
        f.getMessage should ===(message)
        f shouldBe an[E]
      })
    }
  }
}
