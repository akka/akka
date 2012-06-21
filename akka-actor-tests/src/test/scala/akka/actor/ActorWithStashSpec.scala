/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import language.postfixOps

import akka.testkit._
import akka.testkit.DefaultTimeout
import akka.testkit.TestEvent._
import akka.dispatch.Await
import akka.pattern.ask
import akka.util.duration._
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.BeforeAndAfterEach
import org.scalatest.junit.JUnitSuite

object ActorWithStashSpec {

  class StashingActor(implicit sys: ActorSystem) extends Actor with Stash {
    def greeted: Receive = {
      case "bye" ⇒
        state.s = "bye"
        state.finished.await
      case _ ⇒ // do nothing
    }

    def receive = {
      case "hello" ⇒
        state.s = "hello"
        unstashAll()
        context.become(greeted)
      case msg ⇒ stash()
    }
  }

  class StashingTwiceActor(implicit sys: ActorSystem) extends Actor with Stash {
    def receive = {
      case "hello" ⇒
        try {
          stash()
          stash()
        } catch {
          case e: IllegalStateException ⇒
            state.expectedException.open()
        }
      case msg ⇒ // do nothing
    }
  }

  class ActorWithProtocol(implicit sys: ActorSystem) extends Actor with Stash {
    def receive = {
      case "open" ⇒
        unstashAll()
        context.become {
          case "write" ⇒ // do writing...
          case "close" ⇒
            unstashAll()
            context.unbecome()
          case msg ⇒ stash()
        }
      case "done" ⇒ state.finished.await
      case msg    ⇒ stash()
    }
  }

  object state {
    @volatile
    var s: String = ""
    val finished = TestBarrier(2)
    var expectedException: TestLatch = null
  }

  val testConf = """
    my-dispatcher {
      mailbox-type = "akka.dispatch.UnboundedDequeBasedMailbox"
    }
    """

}

class JavaActorWithStashSpec extends StashJavaAPI with JUnitSuite

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ActorWithStashSpec extends AkkaSpec(ActorWithStashSpec.testConf) with DefaultTimeout with BeforeAndAfterEach {
  import ActorWithStashSpec._

  implicit val sys = system

  override def atStartup {
    system.eventStream.publish(Mute(EventFilter[Exception]("Crashing...")))
  }

  override def beforeEach() = state.finished.reset

  def myProps(creator: ⇒ Actor): Props = Props(creator).withDispatcher("my-dispatcher")

  "An Actor with Stash" must {

    "stash messages" in {
      val stasher = system.actorOf(myProps(new StashingActor))
      stasher ! "bye"
      stasher ! "hello"
      state.finished.await
      state.s must be("bye")
    }

    "support protocols" in {
      val protoActor = system.actorOf(myProps(new ActorWithProtocol))
      protoActor ! "open"
      protoActor ! "write"
      protoActor ! "open"
      protoActor ! "close"
      protoActor ! "write"
      protoActor ! "close"
      protoActor ! "done"
      state.finished.await
    }

    "throw an IllegalStateException if the same messages is stashed twice" in {
      state.expectedException = new TestLatch
      val stasher = system.actorOf(myProps(new StashingTwiceActor))
      stasher ! "hello"
      stasher ! "hello"
      Await.ready(state.expectedException, 10 seconds)
    }

    "process stashed messages after restart" in {
      val boss = system.actorOf(myProps(new Supervisor(
        OneForOneStrategy(maxNrOfRetries = 2, withinTimeRange = 1 second)(List(classOf[Throwable])))))

      val restartLatch = new TestLatch
      val hasMsgLatch = new TestLatch

      val slaveProps = myProps(new Actor with Stash {
        def receive = {
          case "crash" ⇒
            throw new Exception("Crashing...")

          // when restartLatch is not yet open, stash all messages != "crash"
          case msg if !restartLatch.isOpen ⇒
            stash()

          // when restartLatch is open, must receive "hello"
          case "hello" ⇒
            hasMsgLatch.open()
        }

        override def preRestart(reason: Throwable, message: Option[Any]) = {
          if (!restartLatch.isOpen)
            restartLatch.open()
          super.preRestart(reason, message)
        }
      })
      val slave = Await.result((boss ? slaveProps).mapTo[ActorRef], timeout.duration)

      slave ! "hello"
      slave ! "crash"

      Await.ready(restartLatch, 10 seconds)
      Await.ready(hasMsgLatch, 10 seconds)
    }
  }
}
