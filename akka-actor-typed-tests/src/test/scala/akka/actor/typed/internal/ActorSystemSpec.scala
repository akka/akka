/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.actor.typed
package internal

import akka.actor.InvalidMessageException
import akka.actor.typed.scaladsl.Actor
import akka.actor.typed.scaladsl.Actor._
import akka.typed.testkit.Inbox
import org.scalactic.ConversionCheckedTripleEquals
import org.scalatest._
import org.scalatest.concurrent.{ Eventually, ScalaFutures }

import scala.concurrent.duration._
import scala.concurrent.{ Future, Promise }
import scala.util.control.NonFatal

class ActorSystemSpec extends WordSpec with Matchers with BeforeAndAfterAll with ScalaFutures with Eventually with ConversionCheckedTripleEquals {

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

  "An ActorSystem" must {
    "must start the guardian actor and terminate when it terminates" in {
      val t = withSystem("a", immutable[Probe] { case (_, p) ⇒ p.replyTo ! p.msg; stopped }, doTerminate = false) { sys ⇒
        val inbox = Inbox[String]("a")
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

    "must terminate the guardian actor" in {
      val inbox = Inbox[String]("terminate")
      val sys = system(
        immutable[Probe] {
          case (_, _) ⇒ unhandled
        } onSignal {
          case (ctx, PostStop) ⇒
            inbox.ref ! "done"
            same
        },
        "terminate")
      sys.terminate().futureValue
      inbox.receiveAll() should ===("done" :: Nil)
    }

    "must log to the event stream" in {
      pending
    }

    "must have a name" in {
      withSystem("name", Actor.empty[String]) { sys ⇒
        sys.name should ===(suite + "-name")
      }
    }

    "must report its uptime" in {
      withSystem("uptime", Actor.empty[String]) { sys ⇒
        sys.uptime should be < 1L
        Thread.sleep(1000)
        sys.uptime should be >= 1L
      }
    }

    "must have a working thread factory" in {
      withSystem("thread", Actor.empty[String]) { sys ⇒
        val p = Promise[Int]
        sys.threadFactory.newThread(new Runnable {
          def run(): Unit = p.success(42)
        }).start()
        p.future.futureValue should ===(42)
      }
    }

    "must be able to run Futures" in {
      withSystem("futures", Actor.empty[String]) { sys ⇒
        val f = Future(42)(sys.executionContext)
        f.futureValue should ===(42)
      }
    }

    "must not allow null messages" in {
      withSystem("null-messages", Actor.empty[String]) { sys ⇒
        intercept[InvalidMessageException] {
          sys ! null
        }
      }
    }
  }
}
