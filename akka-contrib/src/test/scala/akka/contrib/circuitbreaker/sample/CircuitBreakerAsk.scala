package akka.contrib.circuitbreaker.sample

import akka.actor.{ Actor, ActorLogging, ActorRef }
import akka.contrib.circuitbreaker.CircuitBreakerProxy.CircuitBreakerPropsBuilder
import akka.util.Timeout

import scala.concurrent.duration._
import scala.util.{ Failure, Success }

//#ask-sample
class CircuitBreakerAsk(potentiallyFailingService: ActorRef) extends Actor with ActorLogging {
  import SimpleService._
  import akka.pattern._

  implicit val askTimeout: Timeout = 2.seconds

  val serviceCircuitBreaker =
    context.actorOf(
      CircuitBreakerPropsBuilder(maxFailures = 3, callTimeout = askTimeout, resetTimeout = 30.seconds)
        .copy(
          failureDetector = {
            _ match {
              case Response(Left(_)) ⇒ true
              case _                 ⇒ false
            }
          })
        .copy(
          openCircuitFailureConverter = { failure ⇒
            Left(s"Circuit open when processing ${failure.failedMsg}")
          })
        .props(potentiallyFailingService),
      "serviceCircuitBreaker")

  import context.dispatcher

  override def receive: Receive = {
    case AskFor(requestToForward) ⇒
      (serviceCircuitBreaker ? Request(requestToForward)).mapTo[Either[String, String]].onComplete {
        case Success(Right(successResponse)) ⇒
          //handle response
          log.info("Got successful response {}", successResponse)

        case Success(Left(failureResponse)) ⇒
          //handle response
          log.info("Got successful response {}", failureResponse)

        case Failure(exception) ⇒
          //handle response
          log.info("Got successful response {}", exception)

      }
  }
}
//#ask-sample