package akka.actor.dispatch

import java.util.concurrent.{ TimeUnit, CountDownLatch }
import akka.dispatch.{ Mailbox, Dispatchers }
import akka.actor.{ LocalActorRef, IllegalActorStateException, Actor, Props }
import akka.testkit.AkkaSpec

class BalancingDispatcherSpec extends AkkaSpec {

  def newWorkStealer() = app.dispatcherFactory.newBalancingDispatcher("pooled-dispatcher", 1).build

  val delayableActorDispatcher, sharedActorDispatcher, parentActorDispatcher = newWorkStealer()

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

      val slow = actorOf(Props(new DelayableActor(50, finishedCounter)).withDispatcher(delayableActorDispatcher)).asInstanceOf[LocalActorRef]
      val fast = actorOf(Props(new DelayableActor(10, finishedCounter)).withDispatcher(delayableActorDispatcher)).asInstanceOf[LocalActorRef]

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
      fast.underlying.mailbox.asInstanceOf[Mailbox].hasMessages must be(false)
      slow.underlying.mailbox.asInstanceOf[Mailbox].hasMessages must be(false)
      fast.underlyingActorInstance.asInstanceOf[DelayableActor].invocationCount must be > sentToFast
      fast.underlyingActorInstance.asInstanceOf[DelayableActor].invocationCount must be >
        (slow.underlyingActorInstance.asInstanceOf[DelayableActor].invocationCount)
      slow.stop()
      fast.stop()
    }
  }
}
