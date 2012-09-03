package akka.camel.internal

import language.postfixOps
import org.scalatest.matchers.MustMatchers
import scala.concurrent.util.duration._
import org.scalatest.{ GivenWhenThen, BeforeAndAfterEach, BeforeAndAfterAll, WordSpec }
import akka.actor.{ Props, ActorSystem }
import akka.camel._
import akka.testkit.{ TimingTest, TestProbe, TestKit }
import akka.camel.internal.ActivationProtocol._
import scala.concurrent.util.FiniteDuration

class ActivationTrackerTest extends TestKit(ActorSystem("test")) with WordSpec with MustMatchers with BeforeAndAfterAll with BeforeAndAfterEach with GivenWhenThen {

  override protected def afterAll() { system.shutdown() }

  var actor: TestProbe = _
  var awaiting: Awaiting = _
  var anotherAwaiting: Awaiting = _
  val cause = new Exception("cause of failure")

  override protected def beforeEach() {
    actor = TestProbe()
    awaiting = new Awaiting(actor)
    anotherAwaiting = new Awaiting(actor)
  }

  val at = system.actorOf(Props[ActivationTracker], name = "activationTrackker")

  "ActivationTracker forwards activation message to all awaiting parties" taggedAs TimingTest in {
    awaiting.awaitActivation()
    anotherAwaiting.awaitActivation()

    publish(EndpointActivated(actor.ref))

    awaiting.verifyActivated()
    anotherAwaiting.verifyActivated()
  }

  "ActivationTracker send activation message even if activation happened earlier" taggedAs TimingTest in {
    publish(EndpointActivated(actor.ref))
    Thread.sleep(50)
    awaiting.awaitActivation()

    awaiting.verifyActivated()
  }

  "ActivationTracker send activation message even if actor is already deactivated" taggedAs TimingTest in {
    publish(EndpointActivated(actor.ref))
    publish(EndpointDeActivated(actor.ref))
    Thread.sleep(50)
    awaiting.awaitActivation()

    awaiting.verifyActivated()
  }

  "ActivationTracker forwards de-activation message to all awaiting parties" taggedAs TimingTest in {
    publish(EndpointActivated(actor.ref))
    publish(EndpointDeActivated(actor.ref))

    awaiting.awaitDeActivation()
    anotherAwaiting.awaitDeActivation()

    awaiting.verifyDeActivated()
    anotherAwaiting.verifyDeActivated()
  }

  "ActivationTracker forwards de-activation message even if deactivation happened earlier" taggedAs TimingTest in {
    publish(EndpointActivated(actor.ref))

    awaiting.awaitDeActivation()

    publish(EndpointDeActivated(actor.ref))

    awaiting.verifyDeActivated()
  }

  "ActivationTracker forwards de-activation message even if someone awaits de-activation even before activation happens" taggedAs TimingTest in {
    val awaiting = new Awaiting(actor)
    awaiting.awaitDeActivation()

    publish(EndpointActivated(actor.ref))

    publish(EndpointDeActivated(actor.ref))

    awaiting.verifyDeActivated()
  }

  "ActivationTracker sends activation failure when failed to activate" taggedAs TimingTest in {
    awaiting.awaitActivation()
    publish(EndpointFailedToActivate(actor.ref, cause))

    awaiting.verifyFailedToActivate()
  }

  "ActivationTracker sends de-activation failure when failed to de-activate" taggedAs TimingTest in {
    publish(EndpointActivated(actor.ref))
    awaiting.awaitDeActivation()
    publish(EndpointFailedToDeActivate(actor.ref, cause))

    awaiting.verifyFailedToDeActivate()
  }

  "ActivationTracker sends activation message even if it failed to de-activate" taggedAs TimingTest in {
    publish(EndpointActivated(actor.ref))
    publish(EndpointFailedToDeActivate(actor.ref, cause))
    awaiting.awaitActivation()

    awaiting.verifyActivated()
  }

  def publish(msg: AnyRef) = system.eventStream.publish(msg)

  class Awaiting(actor: TestProbe) {
    val probe = TestProbe()
    def awaitActivation() = at.tell(AwaitActivation(actor.ref), probe.ref)
    def awaitDeActivation() = at.tell(AwaitDeActivation(actor.ref), probe.ref)
    def verifyActivated(timeout: FiniteDuration = 50 millis) = within(timeout) { probe.expectMsg(EndpointActivated(actor.ref)) }
    def verifyDeActivated(timeout: FiniteDuration = 50 millis) = within(timeout) { probe.expectMsg(EndpointDeActivated(actor.ref)) }

    def verifyFailedToActivate(timeout: FiniteDuration = 50 millis) = within(timeout) { probe.expectMsg(EndpointFailedToActivate(actor.ref, cause)) }
    def verifyFailedToDeActivate(timeout: FiniteDuration = 50 millis) = within(timeout) { probe.expectMsg(EndpointFailedToDeActivate(actor.ref, cause)) }

  }

}

