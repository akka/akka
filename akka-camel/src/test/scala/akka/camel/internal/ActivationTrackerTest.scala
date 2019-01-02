/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.camel.internal

import org.scalatest.Matchers
import scala.concurrent.duration._
import org.scalatest.{ GivenWhenThen, BeforeAndAfterEach, BeforeAndAfterAll, WordSpecLike }
import akka.actor.{ Props, ActorSystem }
import akka.testkit.{ TimingTest, TestProbe, TestKit }
import akka.camel.internal.ActivationProtocol._

class ActivationTrackerTest extends TestKit(ActorSystem("ActivationTrackerTest")) with WordSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach with GivenWhenThen {

  override protected def afterAll(): Unit = { shutdown() }

  var actor: TestProbe = _
  var awaiting: Awaiting = _
  var anotherAwaiting: Awaiting = _
  val cause = new Exception("cause of failure")

  override protected def beforeEach(): Unit = {
    actor = TestProbe()
    awaiting = new Awaiting(actor)
    anotherAwaiting = new Awaiting(actor)
  }

  val at = system.actorOf(Props[ActivationTracker], name = "activationTrackker")
  "ActivationTracker" must {
    def publish(msg: Any) = at ! msg
    implicit def timeout = remainingOrDefault
    "forwards activation message to all awaiting parties" taggedAs TimingTest in {
      awaiting.awaitActivation()
      anotherAwaiting.awaitActivation()

      publish(EndpointActivated(actor.ref))

      awaiting.verifyActivated()
      anotherAwaiting.verifyActivated()
    }

    "send activation message even if activation happened earlier" taggedAs TimingTest in {
      publish(EndpointActivated(actor.ref))
      Thread.sleep(50)
      awaiting.awaitActivation()

      awaiting.verifyActivated()
    }

    "send activation message even if actor is already deactivated" taggedAs TimingTest in {
      publish(EndpointActivated(actor.ref))
      publish(EndpointDeActivated(actor.ref))
      Thread.sleep(50)
      awaiting.awaitActivation()

      awaiting.verifyActivated()
    }

    "forward de-activation message to all awaiting parties" taggedAs TimingTest in {
      publish(EndpointActivated(actor.ref))
      publish(EndpointDeActivated(actor.ref))

      awaiting.awaitDeActivation()
      anotherAwaiting.awaitDeActivation()

      awaiting.verifyDeActivated()
      anotherAwaiting.verifyDeActivated()
    }

    "forward de-activation message even if deactivation happened earlier" taggedAs TimingTest in {
      publish(EndpointActivated(actor.ref))

      awaiting.awaitDeActivation()

      publish(EndpointDeActivated(actor.ref))

      awaiting.verifyDeActivated()
    }

    "forward de-activation message even if someone awaits de-activation even before activation happens" taggedAs TimingTest in {
      val awaiting = new Awaiting(actor)
      awaiting.awaitDeActivation()

      publish(EndpointActivated(actor.ref))

      publish(EndpointDeActivated(actor.ref))

      awaiting.verifyDeActivated()
    }

    "send activation failure when failed to activate" taggedAs TimingTest in {
      awaiting.awaitActivation()
      publish(EndpointFailedToActivate(actor.ref, cause))

      awaiting.verifyFailedToActivate()
    }

    "send de-activation failure when failed to de-activate" taggedAs TimingTest in {
      publish(EndpointActivated(actor.ref))
      awaiting.awaitDeActivation()
      publish(EndpointFailedToDeActivate(actor.ref, cause))

      awaiting.verifyFailedToDeActivate()
    }

    "send activation message even if it failed to de-activate" taggedAs TimingTest in {
      publish(EndpointActivated(actor.ref))
      publish(EndpointFailedToDeActivate(actor.ref, cause))
      awaiting.awaitActivation()

      awaiting.verifyActivated()
    }

    "send activation message when an actor is activated, deactivated and activated again" taggedAs TimingTest in {
      publish(EndpointActivated(actor.ref))
      publish(EndpointDeActivated(actor.ref))
      publish(EndpointActivated(actor.ref))
      awaiting.awaitActivation()
      awaiting.verifyActivated()
    }
  }

  class Awaiting(actor: TestProbe) {
    val probe = TestProbe()
    def awaitActivation() = at.tell(AwaitActivation(actor.ref), probe.ref)
    def awaitDeActivation() = at.tell(AwaitDeActivation(actor.ref), probe.ref)
    def verifyActivated()(implicit timeout: FiniteDuration) = within(timeout) { probe.expectMsg(EndpointActivated(actor.ref)) }
    def verifyDeActivated()(implicit timeout: FiniteDuration) = within(timeout) { probe.expectMsg(EndpointDeActivated(actor.ref)) }

    def verifyFailedToActivate()(implicit timeout: FiniteDuration) = within(timeout) { probe.expectMsg(EndpointFailedToActivate(actor.ref, cause)) }
    def verifyFailedToDeActivate()(implicit timeout: FiniteDuration) = within(timeout) { probe.expectMsg(EndpointFailedToDeActivate(actor.ref, cause)) }

  }

}

