package akka.actor.dispatch

import java.util.concurrent.{ TimeUnit, CountDownLatch }

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import akka.actor.{ Props, ActorRefWithCell, ActorCell, Actor }
import akka.dispatch.Mailbox
import akka.testkit.AkkaSpec

object BalancingDispatcherSpec {
  val config = """
    pooled-dispatcher {
      type = BalancingDispatcher
      throughput = 1
    }
    """
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class BalancingDispatcherSpec extends AkkaSpec(BalancingDispatcherSpec.config) {

  val delayableActorDispatcher = "pooled-dispatcher"

  class DelayableActor(delay: Int, finishedCounter: CountDownLatch) extends Actor {
    @volatile
    var invocationCount = 0

    def receive = {
      case x: Int ⇒ {
        Thread.sleep(delay)
        invocationCount += 1
        finishedCounter.countDown()
      }
    }
  }

  class FirstActor extends Actor {
    def receive = { case _ ⇒ {} }
  }

  class SecondActor extends Actor {
    def receive = { case _ ⇒ {} }
  }

  class ParentActor extends Actor {
    def receive = { case _ ⇒ {} }
  }

  class ChildActor extends ParentActor {
  }

  "A BalancingDispatcher" must {
    "have fast actor stealing work from slow actor" in {
      val finishedCounter = new CountDownLatch(110)

      val slow = system.actorOf(Props(new DelayableActor(50, finishedCounter)).withDispatcher(delayableActorDispatcher)).asInstanceOf[ActorRefWithCell]
      val fast = system.actorOf(Props(new DelayableActor(10, finishedCounter)).withDispatcher(delayableActorDispatcher)).asInstanceOf[ActorRefWithCell]

      var sentToFast = 0

      for (i ← 1 to 100) {
        // send most work to slow actor
        if (i % 20 == 0) {
          fast ! i
          sentToFast += 1
        } else
          slow ! i
      }

      // now send some messages to actors to keep the dispatcher dispatching messages
      for (i ← 1 to 10) {
        Thread.sleep(150)
        if (i % 2 == 0) {
          fast ! i
          sentToFast += 1
        } else
          slow ! i
      }

      finishedCounter.await(5, TimeUnit.SECONDS)
      fast.underlying.asInstanceOf[ActorCell].mailbox.asInstanceOf[Mailbox].hasMessages must be(false)
      slow.underlying.asInstanceOf[ActorCell].mailbox.asInstanceOf[Mailbox].hasMessages must be(false)
      fast.underlying.asInstanceOf[ActorCell].actor.asInstanceOf[DelayableActor].invocationCount must be > sentToFast
      fast.underlying.asInstanceOf[ActorCell].actor.asInstanceOf[DelayableActor].invocationCount must be >
        (slow.underlying.asInstanceOf[ActorCell].actor.asInstanceOf[DelayableActor].invocationCount)
      system.stop(slow)
      system.stop(fast)
    }
  }
}
