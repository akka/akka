package akka.contrib.circuitbreaker.sample

import akka.actor.{ Actor, ActorLogging, ActorRef }
import akka.contrib.circuitbreaker.CircuitBreakerProxy.{ CircuitBreakerPropsBuilder, CircuitOpenFailure }

import scala.concurrent.duration._

//#basic-sample
class CircuitBreaker(potentiallyFailingService: ActorRef) extends Actor with ActorLogging {
  import SimpleService._

  val serviceCircuitBreaker =
    context.actorOf(
      CircuitBreakerPropsBuilder(maxFailures = 3, callTimeout = 2.seconds, resetTimeout = 30.seconds)
        .copy(
          failureDetector = {
            _ match {
              case Response(Left(_)) ⇒ true
              case _                 ⇒ false
            }
          })
        .props(potentiallyFailingService),
      "serviceCircuitBreaker")

  override def receive: Receive = {
    case AskFor(requestToForward) ⇒
      serviceCircuitBreaker ! Request(requestToForward)

    case Right(Response(content)) ⇒
      //handle response
      log.info("Got successful response {}", content)

    case Response(Right(content)) ⇒
      //handle response
      log.info("Got successful response {}", content)

    case Response(Left(content)) ⇒
      //handle response
      log.info("Got failed response {}", content)

    case CircuitOpenFailure(failedMsg) ⇒
      log.warning("Unable to send message {}", failedMsg)
  }
}
//#basic-sample

