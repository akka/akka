package akka.camel.internal

import org.scalatest.matchers.MustMatchers
import akka.testkit.{ TestProbe, TestKit }
import akka.util.duration._
import org.scalatest.{ GivenWhenThen, BeforeAndAfterEach, BeforeAndAfterAll, WordSpec }
import akka.actor.{ Props, ActorSystem }
import akka.util.Duration
import akka.camel._
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

  val at = system.actorOf(Props[ActivationTracker])

  "ActivationTracker forwards activation message to all awaiting parties" in {
    awaiting.awaitActivation()
    anotherAwaiting.awaitActivation()

    publish(EndpointActivated(actor.ref))

    awaiting.verifyActivated()
    anotherAwaiting.verifyActivated()
  }

  "ActivationTracker send activation message even if activation happened earlier" in {
    publish(EndpointActivated(actor.ref))
    Thread.sleep(50)
    awaiting.awaitActivation()

    awaiting.verifyActivated()
  }

  "ActivationTracker send activation message even if actor is already deactivated" in {
    publish(EndpointActivated(actor.ref))
    publish(EndpointDeActivated(actor.ref))
    Thread.sleep(50)
    awaiting.awaitActivation()

    awaiting.verifyActivated()
  }

  "ActivationTracker forwards de-activation message to all awaiting parties" in {
    given("Actor is activated")
    publish(EndpointActivated(actor.ref))
    given("Actor is deactivated")
    publish(EndpointDeActivated(actor.ref))

    when("Multiple parties await deactivation")
    awaiting.awaitDeActivation()
    anotherAwaiting.awaitDeActivation()

    then("all awaiting parties are notified")
    awaiting.verifyDeActivated()
    anotherAwaiting.verifyDeActivated()
  }

  "ActivationTracker forwards de-activation message even if deactivation happened earlier" in {
    given("Actor is activated")
    publish(EndpointActivated(actor.ref))

    given("Someone is awaiting de-activation")
    awaiting.awaitDeActivation()

    when("Actor is de-activated")
    publish(EndpointDeActivated(actor.ref))

    then("Awaiting gets notified")
    awaiting.verifyDeActivated()
  }

  "ActivationTracker forwards de-activation message even if someone awaits de-activation even before activation happens" in {
    given("Someone is awaiting de-activation")
    val awaiting = new Awaiting(actor)
    awaiting.awaitDeActivation()

    given("Actor is activated")
    publish(EndpointActivated(actor.ref))

    when("Actor is de-activated")
    publish(EndpointDeActivated(actor.ref))

    then("Awaiting gets notified")
    awaiting.verifyDeActivated()
  }

  "ActivationTracker sends activation failure when failed to activate" in {
    awaiting.awaitActivation()
    publish(EndpointFailedToActivate(actor.ref, cause))

    awaiting.verifyFailedToActivate()
  }

  "ActivationTracker sends de-activation failure when failed to de-activate" in {
    publish(EndpointActivated(actor.ref))
    awaiting.awaitDeActivation()
    publish(EndpointFailedToDeActivate(actor.ref, cause))

    awaiting.verifyFailedToDeActivate()
  }

  "ActivationTracker sends activation message even if it failed to de-activate" in {
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
    def verifyActivated(timeout: Duration = 50 millis) = within(timeout) { probe.expectMsg(EndpointActivated(actor.ref)) }
    def verifyDeActivated(timeout: Duration = 50 millis) = within(timeout) { probe.expectMsg(EndpointDeActivated(actor.ref)) }

    def verifyFailedToActivate(timeout: Duration = 50 millis) = within(timeout) { probe.expectMsg(EndpointFailedToActivate(actor.ref, cause)) }
    def verifyFailedToDeActivate(timeout: Duration = 50 millis) = within(timeout) { probe.expectMsg(EndpointFailedToDeActivate(actor.ref, cause)) }

  }

}

