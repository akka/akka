package akka.dispatch

import akka.actor.{ Props, LocalActorRef, Actor }
import akka.testkit.AkkaSpec
import akka.pattern.ask
import akka.util.duration._
import akka.testkit.DefaultTimeout
import com.typesafe.config.Config

object PriorityDispatcherSpec {
  val config = """
    unbounded-prio-dispatcher {
      mailbox-type = "akka.dispatch.PriorityDispatcherSpec$Unbounded"
    }
    bounded-prio-dispatcher {
      mailbox-type = "akka.dispatch.PriorityDispatcherSpec$Bounded"
    }
    """

  class Unbounded(config: Config) extends UnboundedPriorityMailbox(PriorityGenerator({
    case i: Int  ⇒ i //Reverse order
    case 'Result ⇒ Int.MaxValue
  }: Any ⇒ Int))

  class Bounded(config: Config) extends BoundedPriorityMailbox(PriorityGenerator({
    case i: Int  ⇒ i //Reverse order
    case 'Result ⇒ Int.MaxValue
  }: Any ⇒ Int), 1000, 10 seconds)

}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class PriorityDispatcherSpec extends AkkaSpec(PriorityDispatcherSpec.config) with DefaultTimeout {

  "A PriorityDispatcher" must {
    "Order it's messages according to the specified comparator using an unbounded mailbox" in {
      val dispatcherKey = "unbounded-prio-dispatcher"
      testOrdering(dispatcherKey)
    }

    "Order it's messages according to the specified comparator using a bounded mailbox" in {
      val dispatcherKey = "bounded-prio-dispatcher"
      testOrdering(dispatcherKey)
    }
  }

  def testOrdering(dispatcherKey: String) {

    val actor = system.actorOf(Props(new Actor {
      var acc: List[Int] = Nil

      def receive = {
        case i: Int  ⇒ acc = i :: acc
        case 'Result ⇒ sender.tell(acc)
      }
    }).withDispatcher(dispatcherKey)).asInstanceOf[LocalActorRef]

    actor.suspend //Make sure the actor isn't treating any messages, let it buffer the incoming messages

    val msgs = (1 to 100).toList
    for (m ← msgs) actor ! m

    actor.resume //Signal the actor to start treating it's message backlog

    Await.result(actor.?('Result).mapTo[List[Int]], timeout.duration) must be === msgs.reverse
  }

}
