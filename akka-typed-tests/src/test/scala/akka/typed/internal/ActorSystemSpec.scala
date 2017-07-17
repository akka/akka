/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed
package internal

import akka.Done
import akka.typed.scaladsl.Actor
import akka.typed.scaladsl.Actor._
import akka.typed.testkit.Inbox
import akka.util.Timeout
import org.junit.runner.RunWith
import org.scalactic.ConversionCheckedTripleEquals
import org.scalatest._
import org.scalatest.concurrent.{ Eventually, ScalaFutures }

import scala.concurrent.duration._
import scala.concurrent.{ Future, Promise }
import scala.util.control.NonFatal

@RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ActorSystemSpec extends Spec with Matchers with BeforeAndAfterAll with ScalaFutures with Eventually with ConversionCheckedTripleEquals {

  override implicit val patienceConfig = PatienceConfig(1.second)

  case class Probe(msg: String, replyTo: ActorRef[String])

  trait CommonTests {
    def system[T](behavior: Behavior[T], name: String): ActorSystem[T]
    def suite: String

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

    def `must start the guardian actor and terminate when it terminates`(): Unit = {
      val t = withSystem("a", immutable[Probe] { case (_, p) ⇒ p.replyTo ! p.msg; stopped }, doTerminate = false) { sys ⇒
        val inbox = Inbox[String]("a")
        sys ! Probe("hello", inbox.ref)
        eventually { inbox.hasMessages should ===(true) }
        inbox.receiveAll() should ===("hello" :: Nil)
      }
      val p = t.ref.path
      p.name should ===("/")
      p.address.system should ===(suite + "-a")
    }

    def `must terminate the guardian actor`(): Unit = {
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

    def `must log to the event stream`(): Unit = pending

    def `must have a name`(): Unit =
      withSystem("name", Actor.empty[String]) { sys ⇒
        sys.name should ===(suite + "-name")
      }

    def `must report its uptime`(): Unit =
      withSystem("uptime", Actor.empty[String]) { sys ⇒
        sys.uptime should be < 1L
        Thread.sleep(1000)
        sys.uptime should be >= 1L
      }

    def `must have a working thread factory`(): Unit =
      withSystem("thread", Actor.empty[String]) { sys ⇒
        val p = Promise[Int]
        sys.threadFactory.newThread(new Runnable {
          def run(): Unit = p.success(42)
        }).start()
        p.future.futureValue should ===(42)
      }

    def `must be able to run Futures`(): Unit =
      withSystem("futures", Actor.empty[String]) { sys ⇒
        val f = Future(42)(sys.executionContext)
        f.futureValue should ===(42)
      }

  }

  object `An ActorSystemImpl` extends CommonTests {
    def system[T](behavior: Behavior[T], name: String) = ActorSystem(behavior, name)
    def suite = "native"

    // this is essential to complete ActorCellSpec, see there
    def `must correctly treat Watch dead letters`(): Unit =
      withSystem("deadletters", Actor.empty[String]) { sys ⇒
        val client = new DebugRef[Int](sys.path / "debug", true)
        sys.deadLetters.sorry.sendSystem(Watch(sys, client))
        client.receiveAll() should ===(Left(DeathWatchNotification(sys, null)) :: Nil)
      }

    def `must start system actors and mangle their names`(): Unit = {
      withSystem("systemActorOf", Actor.empty[String]) { sys ⇒
        import akka.typed.scaladsl.AskPattern._
        implicit val timeout = Timeout(1.second)
        implicit val sched = sys.scheduler

        case class Doner(ref: ActorRef[Done])

        val ref1, ref2 = sys.systemActorOf(immutable[Doner] {
          case (_, doner) ⇒
            doner.ref ! Done
            same
        }, "empty").futureValue
        (ref1 ? Doner).futureValue should ===(Done)
        (ref2 ? Doner).futureValue should ===(Done)
        val RE = "(\\d+)-empty".r
        val RE(num1) = ref1.path.name.toString
        val RE(num2) = ref2.path.name.toString
        num2.toInt should be > num1.toInt
      }
    }
  }

  object `An ActorSystemAdapter` extends CommonTests {
    def system[T](behavior: Behavior[T], name: String) = ActorSystem.adapter(name, behavior)
    def suite = "adapter"
  }
}
