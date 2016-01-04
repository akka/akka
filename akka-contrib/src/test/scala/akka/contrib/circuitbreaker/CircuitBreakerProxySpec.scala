/**
 * Copyright (C) 2014-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.circuitbreaker

import akka.actor.{ ActorRef, PoisonPill }
import akka.contrib.circuitbreaker.CircuitBreakerProxy._
import akka.testkit.{ AkkaSpec, TestProbe }
import akka.util.Timeout
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{ Millis, Second, Span }
import org.scalatest.{ GivenWhenThen, Matchers }

import scala.concurrent.duration._
import scala.language.postfixOps

class CircuitBreakerProxySpec extends AkkaSpec() with GivenWhenThen with Matchers with ScalaFutures {

  val baseCircuitBreakerPropsBuilder =
    CircuitBreakerPropsBuilder(
      maxFailures = 2,
      callTimeout = 200 millis,
      resetTimeout = 1 second,
      failureDetector = {
        _ == "FAILURE"
      })

  trait CircuitBreakerScenario {
    val sender = TestProbe()
    val eventListener = TestProbe()
    val receiver = TestProbe()

    def circuitBreaker: ActorRef

    def defaultCircuitBreaker = system.actorOf(baseCircuitBreakerPropsBuilder.props(target = receiver.ref))

    def receiverRespondsWithFailureToRequest(request: Any) = {
      sender.send(circuitBreaker, request)
      receiver.expectMsg(request)
      receiver.reply("FAILURE")
      sender.expectMsg("FAILURE")
    }

    def receiverRespondsToRequestWith(request: Any, reply: Any) = {
      sender.send(circuitBreaker, request)
      receiver.expectMsg(request)
      receiver.reply(reply)
      sender.expectMsg(reply)
    }

    def circuitBreakerReceivesSelfNotificationMessage() =
      receiver.expectNoMsg(baseCircuitBreakerPropsBuilder.resetTimeout.duration / 4)

    def resetTimeoutExpires() =
      receiver.expectNoMsg(baseCircuitBreakerPropsBuilder.resetTimeout.duration + 100.millis)

    def callTimeoutExpiresWithoutResponse() =
      sender.expectNoMsg(baseCircuitBreakerPropsBuilder.callTimeout.duration + 100.millis)

    def messageIsRejectedWithOpenCircuitNotification(message: Any) = {
      sender.send(circuitBreaker, message)
      sender.expectMsg(CircuitOpenFailure(message))
    }

  }

  "CircuitBreakerActor" should {

    "act as a transparent proxy in case of successful requests-replies - forward to target" in {

      val sender = TestProbe()
      val receiver = TestProbe()

      val circuitBreaker = system.actorOf(baseCircuitBreakerPropsBuilder.props(target = receiver.ref))

      sender.send(circuitBreaker, "test message")

      receiver.expectMsg("test message")
    }

    "act as a transparent proxy in case of successful requests-replies - full cycle" in {

      val sender = TestProbe()
      val receiver = TestProbe()
      val circuitBreaker = system.actorOf(baseCircuitBreakerPropsBuilder.props(target = receiver.ref))

      sender.send(circuitBreaker, "test message")

      receiver.expectMsg("test message")
      receiver.reply("response")

      sender.expectMsg("response")
    }

    "forward further messages before receiving the response of the first one" in {
      val sender = TestProbe()
      val receiver = TestProbe()
      val circuitBreaker = system.actorOf(baseCircuitBreakerPropsBuilder.props(target = receiver.ref))

      sender.send(circuitBreaker, "test message1")
      sender.send(circuitBreaker, "test message2")
      sender.send(circuitBreaker, "test message3")

      receiver.expectMsg("test message1")
      receiver.expectMsg("test message2")
      receiver.expectMsg("test message3")
    }

    "send responses to the right sender" in {
      val sender1 = TestProbe()
      val sender2 = TestProbe()
      val receiver = TestProbe()
      val circuitBreaker = system.actorOf(baseCircuitBreakerPropsBuilder.props(target = receiver.ref))

      sender1.send(circuitBreaker, "test message1")
      sender2.send(circuitBreaker, "test message2")

      receiver.expectMsg("test message1")
      receiver.reply("response1")

      receiver.expectMsg("test message2")
      receiver.reply("response2")

      sender1.expectMsg("response1")
      sender2.expectMsg("response2")
    }

    "return failed responses too" in {
      val sender = TestProbe()
      val receiver = TestProbe()
      val circuitBreaker = system.actorOf(baseCircuitBreakerPropsBuilder.props(target = receiver.ref))

      sender.send(circuitBreaker, "request")

      receiver.expectMsg("request")
      receiver.reply("FAILURE")

      sender.expectMsg("FAILURE")
    }

    "enter open state after reaching the threshold of failed responses" in new CircuitBreakerScenario {
      val circuitBreaker = defaultCircuitBreaker

      (1 to baseCircuitBreakerPropsBuilder.maxFailures) foreach { index ⇒
        receiverRespondsWithFailureToRequest(s"request$index")
      }

      circuitBreakerReceivesSelfNotificationMessage()

      sender.send(circuitBreaker, "request in open state")
      receiver.expectNoMsg
    }

    "respond with a CircuitOpenFailure message when in open state " in new CircuitBreakerScenario {
      val circuitBreaker = defaultCircuitBreaker

      (1 to baseCircuitBreakerPropsBuilder.maxFailures) foreach { index ⇒
        receiverRespondsWithFailureToRequest(s"request$index")
      }

      circuitBreakerReceivesSelfNotificationMessage()

      sender.send(circuitBreaker, "request in open state")
      sender.expectMsg(CircuitOpenFailure("request in open state"))
    }

    "respond with the converted CircuitOpenFailure if a converter is provided" in new CircuitBreakerScenario {
      val circuitBreaker = system.actorOf(
        baseCircuitBreakerPropsBuilder
          .copy(openCircuitFailureConverter = { failureMsg ⇒ s"NOT SENT: ${failureMsg.failedMsg}" })
          .props(receiver.ref))

      (1 to baseCircuitBreakerPropsBuilder.maxFailures) foreach { index ⇒
        receiverRespondsWithFailureToRequest(s"request$index")
      }

      circuitBreakerReceivesSelfNotificationMessage()

      sender.send(circuitBreaker, "request in open state")
      sender.expectMsg("NOT SENT: request in open state")
    }

    "enter open state after reaching the threshold of timed-out responses" in new CircuitBreakerScenario {
      Given("A circuit breaker actor proxying a test probe")
      val circuitBreaker = defaultCircuitBreaker

      When("A number of request equal to the timed-out responses threashold is done without receiving response within the configured timeout")
      sender.send(circuitBreaker, "request1")
      sender.send(circuitBreaker, "request2")

      callTimeoutExpiresWithoutResponse()

      receiver.expectMsg("request1")
      receiver.reply("this should be timed out 1")

      receiver.expectMsg("request2")
      receiver.reply("this should be timed out 2")

      circuitBreakerReceivesSelfNotificationMessage()

      sender.send(circuitBreaker, "request in open state")
      receiver.expectNoMsg
    }

    "enter HALF OPEN state after the given state timeout, sending the first message only" in new CircuitBreakerScenario {
      Given("A circuit breaker actor proxying a test probe")
      val circuitBreaker = defaultCircuitBreaker

      When("ENTERING OPEN STATE")
      receiverRespondsWithFailureToRequest("request1")
      receiverRespondsWithFailureToRequest("request2")

      circuitBreakerReceivesSelfNotificationMessage()

      Then("Messages are ignored")
      messageIsRejectedWithOpenCircuitNotification("IGNORED SINCE IN OPEN STATE1")
      messageIsRejectedWithOpenCircuitNotification("IGNORED SINCE IN OPEN STATE2")

      When("ENTERING HALF OPEN STATE")
      resetTimeoutExpires()

      Then("First message should be forwarded, following ones ignored if the failure persist")
      sender.send(circuitBreaker, "First message in half-open state, should be forwarded")
      sender.send(circuitBreaker, "Second message in half-open state, should be ignored")

      receiver.expectMsg("First message in half-open state, should be forwarded")
      receiver.expectNoMsg()

      sender.expectMsg(CircuitOpenFailure("Second message in half-open state, should be ignored"))

    }

    "return to CLOSED state from HALF-OPEN if a successful message response notification is received" in new CircuitBreakerScenario {
      Given("A circuit breaker actor proxying a test probe")
      val circuitBreaker = defaultCircuitBreaker

      When("Entering HALF OPEN state")
      receiverRespondsWithFailureToRequest("request1")
      receiverRespondsWithFailureToRequest("request2")

      resetTimeoutExpires()

      And("Receiving a successful response")
      receiverRespondsToRequestWith("First message in half-open state, should be forwarded", "This should close the circuit")

      circuitBreakerReceivesSelfNotificationMessage()

      Then("circuit is re-closed")
      sender.send(circuitBreaker, "request1")
      receiver.expectMsg("request1")

      sender.send(circuitBreaker, "request2")
      receiver.expectMsg("request2")

    }

    "return to OPEN state from HALF-OPEN if a FAILURE message response is received" in new CircuitBreakerScenario {
      Given("A circuit breaker actor proxying a test probe")
      val circuitBreaker = defaultCircuitBreaker

      When("Entering HALF OPEN state")
      receiverRespondsWithFailureToRequest("request1")
      receiverRespondsWithFailureToRequest("request2")

      resetTimeoutExpires()

      And("Receiving a failure response")
      receiverRespondsWithFailureToRequest("First message in half-open state, should be forwarded")

      circuitBreakerReceivesSelfNotificationMessage()

      Then("circuit is opened again")
      sender.send(circuitBreaker, "this should be ignored")
      receiver.expectNoMsg()
      sender.expectMsg(CircuitOpenFailure("this should be ignored"))

    }

    "notify an event status change listener when changing state" in new CircuitBreakerScenario {
      Given("A circuit breaker actor proxying a test probe")
      override val circuitBreaker = system.actorOf(
        baseCircuitBreakerPropsBuilder
          .copy(circuitEventListener = Some(eventListener.ref))
          .props(target = receiver.ref))

      When("Entering OPEN state")
      receiverRespondsWithFailureToRequest("request1")
      receiverRespondsWithFailureToRequest("request2")

      circuitBreakerReceivesSelfNotificationMessage()

      Then("An event is sent")
      eventListener.expectMsg(CircuitOpen(circuitBreaker))

      When("Entering HALF OPEN state")
      resetTimeoutExpires()

      Then("An event is sent")
      eventListener.expectMsg(CircuitHalfOpen(circuitBreaker))

      When("Entering CLOSED state")
      receiverRespondsToRequestWith("First message in half-open state, should be forwarded", "This should close the circuit")
      Then("An event is sent")
      eventListener.expectMsg(CircuitClosed(circuitBreaker))

    }

    "stop if the target actor terminates itself" in new CircuitBreakerScenario {
      Given("An actor that will terminate when receiving a message")
      import akka.actor.ActorDSL._
      val suicidalActor = actor(
        new Act {
          become {
            case anyMessage ⇒
              sender() ! "dying now"
              context stop self
          }
        })

      And("A circuit breaker actor proxying another actor")
      val circuitBreaker = system.actorOf(
        baseCircuitBreakerPropsBuilder.props(target = suicidalActor))

      val suicidalActorWatch = TestProbe()
      suicidalActorWatch.watch(suicidalActor)

      val circuitBreakerWatch = TestProbe()
      circuitBreakerWatch.watch(circuitBreaker)

      When("The target actor stops")
      sender.send(circuitBreaker, "this message will kill the target")
      sender.expectMsg("dying now")
      suicidalActorWatch.expectTerminated(suicidalActor)

      Then("The circuit breaker proxy actor is terminated too")
      circuitBreakerWatch.expectTerminated(circuitBreaker)
    }

    "stop if the target actor is stopped" in new CircuitBreakerScenario {
      Given("A circuit breaker actor proxying a test probe")
      val circuitBreaker = defaultCircuitBreaker

      val receiverActorWatch = TestProbe()
      receiverActorWatch.watch(receiver.ref)

      val circuitBreakerWatch = TestProbe()
      circuitBreakerWatch.watch(circuitBreaker)

      When("The target actor stops")
      sender.send(circuitBreaker, Passthrough(PoisonPill))
      receiverActorWatch.expectTerminated(receiver.ref)

      Then("The circuit breaker proxy actor is terminated too")
      circuitBreakerWatch.expectTerminated(circuitBreaker)
    }

    "send a any message enveloped into a TellOnly case class without expecting a response in closed state" in new CircuitBreakerScenario {
      Given("A circuit breaker actor proxying a test probe")
      val circuitBreaker = defaultCircuitBreaker

      When("A number of request equal to the timed-out responses wrapped in a TellOnly threashold is done without receiving response within the configured timeout")
      sender.send(circuitBreaker, TellOnly("Fire and forget 1"))
      sender.send(circuitBreaker, TellOnly("Fire and forget 2"))
      receiver.expectMsg("Fire and forget 1")
      receiver.expectMsg("Fire and forget 2")

      And("No response is received")
      callTimeoutExpiresWithoutResponse()

      Then("The circuit is still closed")
      sender.send(circuitBreaker, "This should be received too")
      receiver.expectMsg("This should be received too")
    }

    "block messages wrapped in TellOnly when in open state" in new CircuitBreakerScenario {
      Given("A circuit breaker actor proxying a test probe")
      val circuitBreaker = defaultCircuitBreaker

      When("Circuit enters OPEN state")
      (1 to baseCircuitBreakerPropsBuilder.maxFailures) foreach { index ⇒
        receiverRespondsWithFailureToRequest(s"request$index")
      }

      circuitBreakerReceivesSelfNotificationMessage()

      Then("A TellOnly wrapped message is not sent")
      sender.send(circuitBreaker, TellOnly("This should NOT be received"))
      receiver.expectNoMsg()
    }

    "send a any message enveloped into a Passthrough case class without expecting a response even in closed state" in new CircuitBreakerScenario {
      Given("A circuit breaker actor proxying a test probe")
      val circuitBreaker = defaultCircuitBreaker

      When("Circuit enters OPEN state")
      (1 to baseCircuitBreakerPropsBuilder.maxFailures) foreach { index ⇒
        receiverRespondsWithFailureToRequest(s"request$index")
      }

      circuitBreakerReceivesSelfNotificationMessage()

      Then("A Passthrough wrapped message is sent")
      sender.send(circuitBreaker, Passthrough("This should be received"))
      receiver.expectMsg("This should be received")

      And("The circuit is still closed for ordinary messages")
      sender.send(circuitBreaker, "This should NOT be received")
      receiver.expectNoMsg()
    }
  }

  "Ask Extension" should {
    implicit val patienceConfig = PatienceConfig(timeout = Span(1, Second), interval = Span(100, Millis))
    import Implicits.askWithCircuitBreaker

    import scala.concurrent.ExecutionContext.Implicits.global
    implicit val timeout: Timeout = 2.seconds

    "work as a ASK pattern if circuit is closed" in new CircuitBreakerScenario {
      Given("A circuit breaker actor proxying a test probe")
      val circuitBreaker = defaultCircuitBreaker

      When("Doing a askWithCircuitBreaker request")
      val responseFuture = circuitBreaker.askWithCircuitBreaker("request")

      Then("The message is sent to the target actor")
      receiver.expectMsg("request")

      When("Then target actor replies")
      receiver.reply("response")

      Then("The response is available as result of the future returned by the askWithCircuitBreaker method")
      whenReady(responseFuture) { response ⇒
        response should be("response")
      }
    }

    "transform the response into a failure with CircuitOpenException cause if circuit is open" in new CircuitBreakerScenario {
      Given("A circuit breaker actor proxying a test probe")
      val circuitBreaker = defaultCircuitBreaker

      When("The circuit breaker proxy enters OPEN state")
      receiverRespondsWithFailureToRequest("request1")
      receiverRespondsWithFailureToRequest("request2")

      circuitBreakerReceivesSelfNotificationMessage()

      And("Doing a askWithCircuitBreaker request")
      val responseFuture = circuitBreaker.askWithCircuitBreaker("request")

      Then("The message is NOT sent to the target actor")
      receiver.expectNoMsg()

      And("The response is converted into a failure")
      whenReady(responseFuture.failed) { failure ⇒
        failure shouldBe a[OpenCircuitException]
      }
    }
  }

  "Future Extension" should {
    implicit val patienceConfig = PatienceConfig(timeout = Span(1, Second), interval = Span(100, Millis))
    import Implicits.futureExtensions
    import akka.pattern.ask

    import scala.concurrent.ExecutionContext.Implicits.global
    implicit val timeout: Timeout = 2.seconds

    "work as a ASK pattern if circuit is closed" in new CircuitBreakerScenario {
      Given("A circuit breaker actor proxying a test probe")
      val circuitBreaker = defaultCircuitBreaker

      When("Doing a askWithCircuitBreaker request")
      val responseFuture = (circuitBreaker ? "request").failForOpenCircuit

      Then("The message is sent to the target actor")
      receiver.expectMsg("request")

      When("Then target actor replies")
      receiver.reply("response")

      Then("The response is available as result of the future returned by the askWithCircuitBreaker method")
      whenReady(responseFuture) { response ⇒
        response should be("response")
      }
    }

    "transform the response into a failure with CircuitOpenException cause if circuit is open" in new CircuitBreakerScenario {
      Given("A circuit breaker actor proxying a test probe")
      val circuitBreaker = defaultCircuitBreaker

      When("The circuit breaker proxy enters OPEN state")
      receiverRespondsWithFailureToRequest("request1")
      receiverRespondsWithFailureToRequest("request2")

      circuitBreakerReceivesSelfNotificationMessage()

      And("Doing a askWithCircuitBreaker request")
      val responseFuture = (circuitBreaker ? "request").failForOpenCircuit

      Then("The message is NOT sent to the target actor")
      receiver.expectNoMsg()

      And("The response is converted into a failure")
      whenReady(responseFuture.failed) { failure ⇒
        failure shouldBe a[OpenCircuitException]
      }
    }

    "transform the response into a failure with the given exception as cause if circuit is open" in new CircuitBreakerScenario {
      class MyException(message: String) extends Exception(message)

      Given("A circuit breaker actor proxying a test probe")
      val circuitBreaker = defaultCircuitBreaker

      When("The circuit breaker proxy enters OPEN state")
      receiverRespondsWithFailureToRequest("request1")
      receiverRespondsWithFailureToRequest("request2")

      circuitBreakerReceivesSelfNotificationMessage()

      And("Doing a askWithCircuitBreaker request")
      val responseFuture = (circuitBreaker ? "request").failForOpenCircuitWith(new MyException("Circuit is open"))

      Then("The message is NOT sent to the target actor")
      receiver.expectNoMsg()

      And("The response is converted into a failure")
      whenReady(responseFuture.failed) { failure ⇒
        failure shouldBe a[MyException]
        failure.getMessage() should be("Circuit is open")
      }
    }
  }

}
