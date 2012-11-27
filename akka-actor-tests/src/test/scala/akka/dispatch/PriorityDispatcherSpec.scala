package akka.dispatch

import language.postfixOps

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import com.typesafe.config.Config

import akka.actor.{ Props, ActorSystem, Actor }
import akka.pattern.ask
import akka.testkit.{ DefaultTimeout, AkkaSpec }
import scala.concurrent.duration._

object PriorityDispatcherSpec {
  val config = """
    unbounded-prio-dispatcher {
      mailbox-type = "akka.dispatch.PriorityDispatcherSpec$Unbounded"
    }
    bounded-prio-dispatcher {
      mailbox-type = "akka.dispatch.PriorityDispatcherSpec$Bounded"
    }
    """

  class Unbounded(settings: ActorSystem.Settings, config: Config) extends UnboundedPriorityMailbox(PriorityGenerator({
    case i: Int  ⇒ i //Reverse order
    case 'Result ⇒ Int.MaxValue
  }: Any ⇒ Int))

  class Bounded(settings: ActorSystem.Settings, config: Config) extends BoundedPriorityMailbox(PriorityGenerator({
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
    val msgs = (1 to 100) toList

    val actor = system.actorOf(Props(new Actor {

      val acc = scala.collection.mutable.ListBuffer[Int]()

      scala.util.Random.shuffle(msgs) foreach { m ⇒ self ! m }

      self.tell('Result, testActor)

      def receive = {
        case i: Int  ⇒ acc += i
        case 'Result ⇒ sender ! acc.toList
      }
    }).withDispatcher(dispatcherKey))

    expectMsgType[List[_]] must be === msgs
  }

}
