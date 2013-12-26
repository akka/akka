/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.trace

import akka.actor._
import akka.remote.testconductor.RoleName
import akka.remote.testkit.{ STMultiNodeSpec, MultiNodeConfig, MultiNodeSpec }
import akka.testkit._
import com.typesafe.config.ConfigFactory
import scala.concurrent.duration._

object RemoteTransactionSpec extends MultiNodeConfig {
  val one = role("one")
  val two = role("two")

  commonConfig(debugConfig(on = false).withFallback(
    ConfigFactory.parseString("""
      akka.tracers = ["akka.trace.TransactionTracer"]
      akka.remote.log-remote-lifecycle-events = off
    """)))

  case class Message(next: Seq[ActorRef], ids: Seq[String] = Seq.empty)

  class TracedActor extends Actor {
    def receive = {
      case Message(next, ids) ⇒ next.head ! Message(next.tail, ids :+ Transaction.value)
    }
  }
}

class RemoteTransactionMultiJvmNode1 extends RemoteTransactionSpec
class RemoteTransactionMultiJvmNode2 extends RemoteTransactionSpec

class RemoteTransactionSpec extends MultiNodeSpec(RemoteTransactionSpec) with STMultiNodeSpec with ImplicitSender with DefaultTimeout {
  import RemoteTransactionSpec._

  def initialParticipants = roles.size

  def actorName(role: RoleName) = s"actor-${role.name}"

  def identify(role: RoleName): ActorRef = within(10.seconds) {
    system.actorSelection(node(role) / "user" / actorName(role)) ! Identify(actorName(role))
    expectMsgType[ActorIdentity].ref.get
  }

  "Tracer" must {
    "transfer context in remote message flows" taggedAs LongRunningTest in {
      system.actorOf(Props[TracedActor], actorName(myself))
      enterBarrier("started")

      runOn(one) {
        val actorOne = identify(one)
        val actorTwo = identify(two)

        Transaction.withValue("42") {
          actorOne ! Message(Seq(actorTwo, actorOne, testActor))
        }

        expectMsg(Message(Seq.empty, Seq("42", "42", "42")))
      }

      enterBarrier("done")
    }
  }
}
