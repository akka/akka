/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed
package internal

import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.control.NonFatal

import akka.Done
import akka.actor.CoordinatedShutdown
import akka.actor.InvalidMessageException
import akka.actor.testkit.typed.scaladsl.TestInbox
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import org.scalatest._
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.ScalaFutures

class ActorSystemSpec extends WordSpec with Matchers with BeforeAndAfterAll with ScalaFutures with Eventually {

  override implicit val patienceConfig = PatienceConfig(1.second)
  def system[T](behavior: Behavior[T], name: String) = ActorSystem(behavior, name)
  def suite = "adapter"

  case class Probe(message: String, replyTo: ActorRef[String])

  def withSystem[T](name: String, behavior: Behavior[T], doTerminate: Boolean = true)(
      block: ActorSystem[T] => Unit): Unit = {
    val sys = system(behavior, s"$suite-$name")
    try {
      block(sys)
      if (doTerminate) {
        sys.terminate()
        sys.whenTerminated.futureValue
      }
    } catch {
      case NonFatal(ex) =>
        sys.terminate()
        throw ex
    }
  }

  "An ActorSystem" must {
    "start the guardian actor and terminate when it terminates" in {
      withSystem("a", Behaviors.receiveMessage[Probe] { p =>
        p.replyTo ! p.message
        Behaviors.stopped
      }, doTerminate = false) { sys =>
        val inbox = TestInbox[String]("a")
        sys ! Probe("hello", inbox.ref)
        eventually {
          inbox.hasMessages should ===(true)
        }
        inbox.receiveAll() should ===("hello" :: Nil)
        sys.whenTerminated.futureValue
        CoordinatedShutdown(sys.toUntyped).shutdownReason() should ===(
          Some(CoordinatedShutdown.ActorSystemTerminateReason))
      }
    }

    // see issue #24172
    "shutdown if guardian shuts down immediately" in {
      val stoppable =
        Behaviors.receiveMessage[Done] { _ =>
          Behaviors.stopped
        }
      withSystem("shutdown", stoppable, doTerminate = false) { sys: ActorSystem[Done] =>
        sys ! Done
        sys.whenTerminated.futureValue
      }
    }

    "terminate the guardian actor" in {
      val inbox = TestInbox[String]("terminate")
      val sys = system(Behaviors.setup[Any] { _ =>
        inbox.ref ! "started"
        Behaviors.receiveSignal {
          case (_, PostStop) =>
            inbox.ref ! "done"
            Behaviors.same
        }
      }, "terminate")

      eventually {
        inbox.hasMessages should ===(true)
      }
      inbox.receiveAll() should ===("started" :: Nil)

      // now we know that the guardian has started, and should receive PostStop
      sys.terminate()
      sys.whenTerminated.futureValue
      CoordinatedShutdown(sys.toUntyped).shutdownReason() should ===(
        Some(CoordinatedShutdown.ActorSystemTerminateReason))
      inbox.receiveAll() should ===("done" :: Nil)
    }

    "be able to terminate immediately" in {
      val sys = system(Behaviors.receiveMessage[Probe] { _ =>
        Behaviors.unhandled
      }, "terminate")
      // for this case the guardian might not have been started before
      // the system terminates and then it will not receive PostStop, which
      // is OK since it wasn't really started yet
      sys.terminate()
      sys.whenTerminated.futureValue
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
