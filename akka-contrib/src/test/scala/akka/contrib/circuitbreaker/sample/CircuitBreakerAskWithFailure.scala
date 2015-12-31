package akka.contrib.circuitbreaker.sample

import akka.actor.{ Actor, ActorLogging, ActorRef }
import akka.contrib.circuitbreaker.CircuitBreakerActor._
import akka.util.Timeout

import scala.concurrent.duration._
import scala.util.{ Failure, Success }

//#ask-with-failure-sample
class CircuitBreakerAskWithFailure(potentiallyFailingService: ActorRef) extends Actor with ActorLogging {
  import SimpleService._
  import akka.pattern._

  implicit val askTimeout: Timeout = 2.seconds

  val serviceCircuitBreaker =
    context.actorOf(
      CircuitBreakerActorPropsBuilder(maxFailures = 3, callTimeout = askTimeout, resetTimeout = 30.seconds).props(potentiallyFailingService),
      "serviceCircuitBreaker")

  import context.dispatcher

  override def receive: Receive = {
    case AskFor(requestToForward) ⇒
      (serviceCircuitBreaker ? Request(requestToForward)).failForOpenCircuit.mapTo[String].onComplete {
        case Success(successResponse) ⇒
          //handle response
          log.info("Got successful response {}", successResponse)

        case Failure(exception) ⇒
          //handle response
          log.info("Got successful response {}", exception)

      }
  }
}
//#ask-with-failure-sample