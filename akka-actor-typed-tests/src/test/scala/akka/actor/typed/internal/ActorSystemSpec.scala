/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed
package internal

import akka.Done
import akka.actor.InvalidMessageException
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.testkit.typed.scaladsl.TestInbox
import org.scalatest._
import org.scalatest.concurrent.{ Eventually, ScalaFutures }

import scala.concurrent.duration._
import scala.concurrent.{ Future, Promise }
import scala.util.control.NonFatal

class ActorSystemSpec extends WordSpec with Matchers with BeforeAndAfterAll with ScalaFutures with Eventually {

  override implicit val patienceConfig = PatienceConfig(1.second)
  def system[T](behavior: Behavior[T], name: String) = ActorSystem(behavior, name)
  def suite = "adapter"

  case class Probe(message: String, replyTo: ActorRef[String])

  def withSystem[T](name: String, behavior: Behavior[T], doTerminate: Boolean = true)(
      block: ActorSystem[T] => Unit): Terminated = {
    val sys = system(behavior, s"$suite-$name")
    try {
      block(sys)
      if (doTerminate) sys.terminate().futureValue else sys.whenTerminated.futureValue
    } catch {
      case NonFatal(ex) =>
        sys.terminate()
        throw ex
    }
  }

  "An ActorSystem" must {
    "start the guardian actor and terminate when it terminates" in {
      val t = withSystem(
        "a",
        Behaviors.receive[Probe] { case (_, p) => p.replyTo ! p.message; Behaviors.stopped },
        doTerminate = false) { sys =>
        val inbox = TestInbox[String]("a")
        sys ! Probe("hello", inbox.ref)
        eventually {
          inbox.hasMessages should ===(true)
        }
        inbox.receiveAll() should ===("hello" :: Nil)
      }
      val p = t.ref.path
      p.name should ===("/")
      p.address.system should ===(suite + "-a")
    }

    // see issue #24172
    "shutdown if guardian shuts down immediately" in {
      val stoppable =
        Behaviors.receive[Done] {
          case (context, Done) => Behaviors.stopped
        }
      withSystem("shutdown", stoppable, doTerminate = false) { sys: ActorSystem[Done] =>
        sys ! Done
        sys.whenTerminated.futureValue
      }
    }

    "terminate the guardian actor" in {
      val inbox = TestInbox[String]("terminate")
      val sys = system(
        Behaviors
          .receive[Probe] {
            case (_, _) => Behaviors.unhandled
          }
          .receiveSignal {
            case (_, PostStop) =>
              inbox.ref ! "done"
              Behaviors.same
          },
        "terminate")
      sys.terminate().futureValue
      inbox.receiveAll() should ===("done" :: Nil)
    }

    "log to the event stream" in {
      pending
    }

    "have a name" in {
      withSystem("name", Behaviors.empty[String]) { sys =>
        sys.name should ===(suite + "-name")
      }
    }

    "report its uptime" in {
      withSystem("uptime", Behaviors.empty[String]) { sys =>
        sys.uptime should be < 1L
        Thread.sleep(1000)
        sys.uptime should be >= 1L
      }
    }

    "have a working thread factory" in {
      withSystem("thread", Behaviors.empty[String]) { sys =>
        val p = Promise[Int]
        sys.threadFactory
          .newThread(new Runnable {
            def run(): Unit = p.success(42)
          })
          .start()
        p.future.futureValue should ===(42)
      }
    }

    "be able to run Futures" in {
      withSystem("futures", Behaviors.empty[String]) { sys =>
        val f = Future(42)(sys.executionContext)
        f.futureValue should ===(42)
      }
    }

    "not allow null messages" in {
      withSystem("null-messages", Behaviors.empty[String]) { sys =>
        intercept[InvalidMessageException] {
          sys ! null
        }
      }
    }
  }
}
