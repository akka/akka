/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import language.postfixOps

import akka.testkit._
import akka.testkit.DefaultTimeout
import akka.testkit.TestEvent._
import scala.concurrent.duration._
import akka.routing._
import org.scalatest.BeforeAndAfterEach
import akka.ConfigurationException

object ActorConfigurationVerificationSpec {

  class TestActor extends Actor {
    def receive: Receive = {
      case _ ⇒
    }
  }

  val config = """
    balancing-dispatcher {
      type = "akka.dispatch.BalancingDispatcherConfigurator"
      throughput = 1
    }
    pinned-dispatcher {
      executor = "thread-pool-executor"
      type = PinnedDispatcher
    }
    """
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ActorConfigurationVerificationSpec extends AkkaSpec(ActorConfigurationVerificationSpec.config) with DefaultTimeout with BeforeAndAfterEach {
  import ActorConfigurationVerificationSpec._

  override def atStartup {
    system.eventStream.publish(Mute(EventFilter[ConfigurationException]("")))
  }

  "An Actor configured with a BalancingDispatcher" must {
    "fail verification with a ConfigurationException if also configured with a RoundRobinRouter" in {
      intercept[ConfigurationException] {
        system.actorOf(RoundRobinRouter(2).withDispatcher("balancing-dispatcher").props(Props[TestActor]))
      }
    }
    "fail verification with a ConfigurationException if also configured with a BroadcastRouter" in {
      intercept[ConfigurationException] {
        system.actorOf(BroadcastRouter(2).withDispatcher("balancing-dispatcher").props(Props[TestActor]))
      }
    }
    "fail verification with a ConfigurationException if also configured with a RandomRouter" in {
      intercept[ConfigurationException] {
        system.actorOf(RandomRouter(2).withDispatcher("balancing-dispatcher").props(Props[TestActor]))
      }
    }
    "fail verification with a ConfigurationException if also configured with a SmallestMailboxRouter" in {
      intercept[ConfigurationException] {
        system.actorOf(SmallestMailboxRouter(2).withDispatcher("balancing-dispatcher").props(Props[TestActor]))
      }
    }
    "fail verification with a ConfigurationException if also configured with a ScatterGatherFirstCompletedRouter" in {
      intercept[ConfigurationException] {
        system.actorOf(ScatterGatherFirstCompletedRouter(nrOfInstances = 2, within = 2 seconds).
          withDispatcher("balancing-dispatcher").props(Props[TestActor]))
      }
    }
    "not fail verification with a ConfigurationException also not configured with a Router" in {
      system.actorOf(Props[TestActor].withDispatcher("balancing-dispatcher"))
    }
  }
  "An Actor configured with a non-balancing dispatcher" must {
    "not fail verification with a ConfigurationException if also configured with a Router" in {
      system.actorOf(RoundRobinRouter(2).props(Props[TestActor].withDispatcher("pinned-dispatcher")))
    }

    "fail verification if the dispatcher cannot be found" in {
      intercept[ConfigurationException] {
        system.actorOf(Props[TestActor].withDispatcher("does not exist"))
      }
    }

    "fail verification if the dispatcher cannot be found for the head of a router" in {
      intercept[ConfigurationException] {
        system.actorOf(RoundRobinRouter(1, routerDispatcher = "does not exist").props(Props[TestActor]))
      }
    }

    "fail verification if the dispatcher cannot be found for the routees of a router" in {
      intercept[ConfigurationException] {
        system.actorOf(RoundRobinRouter(1).props(Props[TestActor].withDispatcher("does not exist")))
      }
    }
  }
}
