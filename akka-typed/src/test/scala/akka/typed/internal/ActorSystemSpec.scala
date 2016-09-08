/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed
package internal

import org.junit.runner.RunWith
import org.scalactic.ConversionCheckedTripleEquals
import org.scalatest._
import org.scalatest.exceptions.TestFailedException
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.concurrent.Eventually
import scala.concurrent.duration._
import scala.concurrent.{ Future, Promise }
import scala.util.control.NonFatal

@RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ActorSystemSpec extends Spec with Matchers with BeforeAndAfterAll with ScalaFutures with Eventually with ConversionCheckedTripleEquals {
  import ScalaDSL._

  override implicit val patienceConfig = PatienceConfig(1.second)

  case class Probe(msg: String, replyTo: ActorRef[String])

  trait CommonTests {
    def system[T](name: String, props: Props[T]): ActorSystem[T]
    def suite: String

    def withSystem[T](name: String, props: Props[T], doTerminate: Boolean = true)(block: ActorSystem[T] ⇒ Unit): Terminated = {
      val sys = system(s"$suite-$name", props)
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
      val t = withSystem("a", Props(Total[Probe] { p ⇒ p.replyTo ! p.msg; Stopped }), doTerminate = false) { sys ⇒
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
      val sys = system("terminate", Props(Full[Probe] {
        case Sig(ctx, PostStop) ⇒
          inbox.ref ! "done"
          Same
      }))
      sys.terminate().futureValue
      inbox.receiveAll() should ===("done" :: Nil)
    }

    def `must log to the event stream`(): Unit = pending

    def `must have a name`(): Unit =
      withSystem("name", Props(Empty[String])) { sys ⇒
        sys.name should ===(suite + "-name")
      }

    def `must report its uptime`(): Unit =
      withSystem("uptime", Props(Empty[String])) { sys ⇒
        sys.uptime should be < 1L
        Thread.sleep(1000)
        sys.uptime should be >= 1L
      }

    def `must have a working thread factory`(): Unit =
      withSystem("thread", Props(Empty[String])) { sys ⇒
        val p = Promise[Int]
        sys.threadFactory.newThread(new Runnable {
          def run(): Unit = p.success(42)
        }).start()
        p.future.futureValue should ===(42)
      }

    def `must be able to run Futures`(): Unit =
      withSystem("futures", Props(Empty[String])) { sys ⇒
        val f = Future(42)(sys.executionContext)
        f.futureValue should ===(42)
      }

  }

  object `An ActorSystemImpl` extends CommonTests {
    def system[T](name: String, props: Props[T]): ActorSystem[T] = ActorSystem(name, props)
    def suite = "native"

    // this is essential to complete ActorCellSpec, see there
    def `must correctly treat Watch dead letters`(): Unit =
      withSystem("deadletters", Props(Empty[String])) { sys ⇒
        val client = new DebugRef[Int](sys.path / "debug", true)
        sys.deadLetters.sorry.sendSystem(Watch(sys, client))
        client.receiveAll() should ===(Left(DeathWatchNotification(sys, null)) :: Nil)
      }
  }

  object `An ActorSystemAdapter` extends CommonTests {
    def system[T](name: String, props: Props[T]): ActorSystem[T] = ActorSystem.adapter(name, props)
    def suite = "adapter"
  }
}
