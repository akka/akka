/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.dispatch

import language.postfixOps

import com.typesafe.config.Config

import akka.actor.{ Props, ActorSystem, Actor }
import akka.testkit.{ DefaultTimeout, AkkaSpec }
import scala.concurrent.duration._

object StablePriorityDispatcherSpec {
  val config = """
    unbounded-stable-prio-dispatcher {
      mailbox-type = "akka.dispatch.StablePriorityDispatcherSpec$Unbounded"
    }
    bounded-stable-prio-dispatcher {
      mailbox-type = "akka.dispatch.StablePriorityDispatcherSpec$Bounded"
    }
    """

  class Unbounded(settings: ActorSystem.Settings, config: Config) extends UnboundedStablePriorityMailbox(PriorityGenerator({
    case i: Int if i <= 100 ⇒ i // Small integers have high priority
    case i: Int             ⇒ 101 // Don't care for other integers
    case 'Result            ⇒ Int.MaxValue
  }: Any ⇒ Int))

  class Bounded(settings: ActorSystem.Settings, config: Config) extends BoundedStablePriorityMailbox(PriorityGenerator({
    case i: Int if i <= 100 ⇒ i // Small integers have high priority
    case i: Int             ⇒ 101 // Don't care for other integers
    case 'Result            ⇒ Int.MaxValue
  }: Any ⇒ Int), 1000, 10 seconds)

}

class StablePriorityDispatcherSpec extends AkkaSpec(StablePriorityDispatcherSpec.config) with DefaultTimeout {

  "A StablePriorityDispatcher" must {
    "Order its messages according to the specified comparator while preserving FIFO for equal priority messages, " +
      "using an unbounded mailbox" in {
        val dispatcherKey = "unbounded-stable-prio-dispatcher"
        testOrdering(dispatcherKey)
      }

    "Order its messages according to the specified comparator while preserving FIFO for equal priority messages, " +
      "using a bounded mailbox" in {
        val dispatcherKey = "bounded-stable-prio-dispatcher"
        testOrdering(dispatcherKey)
      }

    def testOrdering(dispatcherKey: String): Unit = {
      val msgs = (1 to 200) toList
      val shuffled = scala.util.Random.shuffle(msgs)

      // It's important that the actor under test is not a top level actor
      // with RepointableActorRef, since messages might be queued in
      // UnstartedCell and then sent to the StablePriorityQueue and consumed immediately
      // without the ordering taking place.
      val actor = system.actorOf(Props(new Actor {
        context.actorOf(Props(new Actor {

          val acc = scala.collection.mutable.ListBuffer[Int]()

          shuffled foreach { m ⇒ self ! m }

          self.tell('Result, testActor)

          def receive = {
            case i: Int  ⇒ acc += i
            case 'Result ⇒ sender() ! acc.toList
          }
        }).withDispatcher(dispatcherKey))

        def receive = Actor.emptyBehavior

      }))

      // Low messages should come out first, and in priority order.  High messages follow - they are equal priority and
      // should come out in the same order in which they were sent.
      val lo = (1 to 100) toList
      val hi = shuffled filter { _ > 100 }
      expectMsgType[List[Int]] should ===(lo ++ hi)
    }
  }
}
