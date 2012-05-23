/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import akka.testkit._
import akka.testkit.DefaultTimeout
import akka.testkit.TestEvent._
import akka.dispatch.Await
import akka.util.duration._
import akka.routing._
import akka.config.ConfigurationException
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.BeforeAndAfterEach
import org.scalatest.junit.JUnitSuite

object ActorConfigurationVerificationSpec {

  class TestActor extends Actor {
    def receive: Receive = {
      case _ ⇒
    }
  }

  val config = """
    balancing-dispatcher {
      type = BalancingDispatcher
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
        system.actorOf(Props[TestActor].withDispatcher("balancing-dispatcher").withRouter(RoundRobinRouter(2)))
      }
    }
    "fail verification with a ConfigurationException if also configured with a BroadcastRouter" in {
      intercept[ConfigurationException] {
        system.actorOf(Props[TestActor].withDispatcher("balancing-dispatcher").withRouter(BroadcastRouter(2)))
      }
    }
    "fail verification with a ConfigurationException if also configured with a RandomRouter" in {
      intercept[ConfigurationException] {
        system.actorOf(Props[TestActor].withDispatcher("balancing-dispatcher").withRouter(RandomRouter(2)))
      }
    }
    "fail verification with a ConfigurationException if also configured with a SmallestMailboxRouter" in {
      intercept[ConfigurationException] {
        system.actorOf(Props[TestActor].withDispatcher("balancing-dispatcher").withRouter(SmallestMailboxRouter(2)))
      }
    }
    "fail verification with a ConfigurationException if also configured with a ScatterGatherFirstCompletedRouter" in {
      intercept[ConfigurationException] {
        system.actorOf(Props[TestActor].withDispatcher("balancing-dispatcher").withRouter(ScatterGatherFirstCompletedRouter(nrOfInstances = 2, within = 2 seconds)))
      }
    }
    "not fail verification with a ConfigurationException also not configured with a Router" in {
      system.actorOf(Props[TestActor].withDispatcher("balancing-dispatcher"))
    }
  }
  "An Actor configured with a non-balancing dispatcher" must {
    "not fail verification with a ConfigurationException if also configured with a Router" in {
      system.actorOf(Props[TestActor].withDispatcher("pinned-dispatcher").withRouter(RoundRobinRouter(2)))
    }
  }
}
