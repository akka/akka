/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor

import language.postfixOps

import akka.testkit._
import akka.testkit.DefaultTimeout
import akka.testkit.TestEvent._
import scala.concurrent.Await
import akka.pattern.ask
import scala.concurrent.duration._
import org.scalatest.BeforeAndAfterEach
import org.scalatestplus.junit.JUnitSuiteLike

object ActorWithStashSpec {

  class StashingActor extends Actor with Stash {
    import context.system
    def greeted: Receive = {
      case "bye" =>
        state.s = "bye"
        state.finished.await
      case _ => // do nothing
    }

    def receive = {
      case "hello" =>
        state.s = "hello"
        unstashAll()
        context.become(greeted)
      case msg => stash()
    }
  }

  class StashingTwiceActor extends Actor with Stash {
    def receive = {
      case "hello" =>
        try {
          stash()
          stash()
        } catch {
          case e: IllegalStateException =>
            state.expectedException.open()
        }
      case msg => // do nothing
    }
  }

  class ActorWithProtocol extends Actor with Stash {
    import context.system
    def receive = {
      case "open" =>
        unstashAll()
        context.become {
          case "write" => // do writing...
          case "close" =>
            unstashAll()
            context.unbecome()
          case msg => stash()
        }
      case "done" => state.finished.await
      case msg    => stash()
    }
  }

  class WatchedActor extends Actor {
    def receive = Actor.emptyBehavior
  }

  class TerminatedMessageStashingActor(probe: ActorRef) extends Actor with Stash {
    val watched = context.watch(context.actorOf(Props[WatchedActor]))
    var stashed = false

    context.stop(watched)

    def receive = {
      case Terminated(`watched`) =>
        if (!stashed) {
          stash()
          stashed = true
          unstashAll()
        }
        probe ! "terminated"
    }
  }

  object state {
    @volatile
    var s: String = ""
    val finished = TestBarrier(2)
    var expectedException: TestLatch = null
  }

  val testConf = """
    akka.actor.serialize-messages = off
    """

}

class JavaActorWithStashSpec extends StashJavaAPI with JUnitSuiteLike

class ActorWithStashSpec extends AkkaSpec(ActorWithStashSpec.testConf) with DefaultTimeout with BeforeAndAfterEach {
  import ActorWithStashSpec._

  override def atStartup: Unit = {
    system.eventStream.publish(Mute(EventFilter[Exception]("Crashing...")))
  }

  override def beforeEach() = state.finished.reset

  "An Actor with Stash" must {

    "stash messages" in {
      val stasher = system.actorOf(Props(new StashingActor))
      stasher ! "bye"
      stasher ! "hello"
      state.finished.await
      state.s should ===("bye")
    }

    "support protocols" in {
      val protoActor = system.actorOf(Props[ActorWithProtocol])
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
      val stasher = system.actorOf(Props[StashingTwiceActor])
      stasher ! "hello"
      stasher ! "hello"
      Await.ready(state.expectedException, 10 seconds)
    }

    "process stashed messages after restart" in {
      val boss = system.actorOf(
        Props(
          new Supervisor(OneForOneStrategy(maxNrOfRetries = 2, withinTimeRange = 1 second)(List(classOf[Throwable])))))

      val restartLatch = new TestLatch
      val hasMsgLatch = new TestLatch

      val slaveProps = Props(new Actor with Stash {
        def receive = {
          case "crash" =>
            throw new Exception("Crashing...")

          // when restartLatch is not yet open, stash all messages != "crash"
          case msg if !restartLatch.isOpen =>
            stash()

          // when restartLatch is open, must receive "hello"
          case "hello" =>
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

    "re-receive unstashed Terminated messages" in {
      system.actorOf(Props(classOf[TerminatedMessageStashingActor], testActor))
      expectMsg("terminated")
      expectMsg("terminated")
    }
  }

  "An ActWithStash" must {

    "allow using whenRestarted" in {
      import ActorDSL._
      val a = actor(new ActWithStash {
        become {
          case "die" => throw new RuntimeException("dying")
        }
        whenRestarted { thr =>
          testActor ! "restarted"
        }
      })
      EventFilter[RuntimeException]("dying", occurrences = 1).intercept {
        a ! "die"
      }
      expectMsg("restarted")
    }

    "allow using whenStopping" in {
      import ActorDSL._
      val a = actor(new ActWithStash {
        whenStopping {
          testActor ! "stopping"
        }
      })
      a ! PoisonPill
      expectMsg("stopping")
    }

  }
}
