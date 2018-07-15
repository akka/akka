/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.adapter

import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.control.NonFatal

import akka.Done
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior, Terminated }
import akka.actor.typed.scaladsl.Behaviors
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpec, Inside }
import org.scalatest.concurrent.{ Eventually, ScalaFutures }

class WhenTerminatedSpec extends WordSpec with Matchers with BeforeAndAfterAll
  with Inside with ScalaFutures with Eventually {

  override implicit val patienceConfig = PatienceConfig(1.second)

  def system[T](behavior: Behavior[T], name: String) = ActorSystem(behavior, name)

  def suite = "adapter"

  case class Probe(msg: String, replyTo: ActorRef[String])

  def withSystem[T](name: String, behavior: Behavior[T], doTerminate: Boolean = true)(block: ActorSystem[T] ⇒ Unit): Terminated = {
    val sys = system(behavior, s"$suite-$name")
    try {
      block(sys)
      if (doTerminate) sys.terminate().futureValue else sys.whenTerminated.futureValue
    } catch {
      case NonFatal(ex) ⇒
        sys.terminate()
        throw ex
    }
  }

  class MyException(val counter: Int) extends Exception(counter.toString)

  sealed trait Request

  case object Next extends Request

  case object Stop extends Request

  case object Throw extends Request

  def actor(counter: Int): Behavior[Request] =
    Behaviors.receiveMessage[Request] {
      case Next  ⇒ actor(counter + 1)
      case Stop  ⇒ Behaviors.stopped
      case Throw ⇒ throw new MyException(counter)
    }

  "A WhenTerminated" must {
    "complete future with exception if guardian throws" in {

      val promise = Promise[Terminated]()
      val wrapped = WhenTerminated(actor(0), promise)
      withSystem("exception", wrapped, doTerminate = false) { sys: ActorSystem[Request] ⇒
        sys ! Throw

        val terminated1 = sys.whenTerminated.futureValue
        val terminated2 = promise.future.futureValue

        inside(terminated2.failure) {
          case Some(t: MyException) ⇒ t.counter shouldBe 0
        }
      }
    }

    "complete future without exception if guardian stops" in {

      val promise = Promise[Terminated]()
      val wrapped = WhenTerminated(actor(0), promise)
      withSystem("exception", wrapped, doTerminate = false) { sys: ActorSystem[Request] ⇒
        sys ! Stop

        val terminated1 = sys.whenTerminated.futureValue
        val terminated2 = promise.future.futureValue
        terminated2.failure shouldBe None
      }
    }

    "complete future with exception if guardian throws (after changing behavior" in {

      val promise = Promise[Terminated]()
      val wrapped = WhenTerminated(actor(0), promise)
      withSystem("exception", wrapped, doTerminate = false) { sys: ActorSystem[Request] ⇒
        sys ! Next
        sys ! Throw

        val terminated1 = sys.whenTerminated.futureValue
        val terminated2 = promise.future.futureValue
        inside(terminated2.failure) {
          case Some(t: MyException) ⇒ t.counter shouldBe 1
        }
      }
    }

    "complete future without exception if guardian stops (after changing behavior)" in {

      val promise = Promise[Terminated]()
      val wrapped = WhenTerminated(actor(0), promise)
      withSystem("exception", wrapped, doTerminate = false) { sys: ActorSystem[Request] ⇒
        sys ! Next
        sys ! Stop

        val terminated1 = sys.whenTerminated.futureValue
        val terminated2 = promise.future.futureValue
        terminated2.failure shouldBe None
      }
    }

    "complete future without exception if guardian stops (deferred)" in {

      val promise = Promise[Terminated]()
      val wrapped = WhenTerminated(Behaviors.setup[Request](_ ⇒ actor(0)), promise)
      withSystem("exception", wrapped, doTerminate = false) { sys: ActorSystem[Request] ⇒
        sys ! Stop

        val terminated1 = sys.whenTerminated.futureValue
        val terminated2 = promise.future.futureValue
        terminated2.failure shouldBe None
      }
    }

    "complete future when behavior is empty" in {
      val promise = Promise[Terminated]()
      val wrapped = WhenTerminated(Behaviors.empty[Request], promise)
      withSystem("exception", wrapped, doTerminate = false) { sys: ActorSystem[Request] ⇒

        sys.terminate()

        val terminated1 = sys.whenTerminated.futureValue
        val terminated2 = promise.future.futureValue
        terminated2.failure shouldBe None
      }
    }
  }
}
