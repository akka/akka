/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.contrib.circuitbreaker.sample

import akka.actor.{ Actor, ActorLogging, ActorRef }
import akka.contrib.circuitbreaker.CircuitBreakerProxy.{ CircuitBreakerPropsBuilder, CircuitOpenFailure }
import akka.contrib.circuitbreaker.sample.CircuitBreaker.AskFor
import akka.util.Timeout

import scala.concurrent.duration._
import scala.util.{ Failure, Success, Random }

//#simple-service
object SimpleService {
  case class Request(content: String)
  case class Response(content: Either[String, String])
  case object ResetCount
}

/**
 * This is a simple actor simulating a service
 * - Becoming slower with the increase of frequency of input requests
 * - Failing around 30% of the requests
 */
class SimpleService extends Actor with ActorLogging {
  import SimpleService._

  var messageCount = 0

  import context.dispatcher

  context.system.scheduler.schedule(1.second, 1.second, self, ResetCount)

  override def receive = {
    case ResetCount ⇒
      messageCount = 0

    case Request(content) ⇒
      messageCount += 1
      // simulate workload
      Thread.sleep(100 * messageCount)
      // Fails around 30% of the times
      if (Random.nextInt(100) < 70) {
        sender ! Response(Right(s"Successfully processed $content"))
      } else {
        sender ! Response(Left(s"Failure processing $content"))
      }

  }
}
//#simple-service

object CircuitBreaker {
  case class AskFor(what: String)
}

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

//#ask-with-failure-sample
class CircuitBreakerAskWithFailure(potentiallyFailingService: ActorRef) extends Actor with ActorLogging {
  import SimpleService._
  import akka.pattern._
  import akka.contrib.circuitbreaker.Implicits.futureExtensions

  implicit val askTimeout: Timeout = 2.seconds

  val serviceCircuitBreaker =
    context.actorOf(
      CircuitBreakerPropsBuilder(maxFailures = 3, callTimeout = askTimeout, resetTimeout = 30.seconds)
        .props(target = potentiallyFailingService),
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

//#ask-with-circuit-breaker-sample
class CircuitBreakerAskWithCircuitBreaker(potentiallyFailingService: ActorRef) extends Actor with ActorLogging {
  import SimpleService._
  import akka.contrib.circuitbreaker.Implicits.askWithCircuitBreaker

  implicit val askTimeout: Timeout = 2.seconds

  val serviceCircuitBreaker =
    context.actorOf(
      CircuitBreakerPropsBuilder(maxFailures = 3, callTimeout = askTimeout, resetTimeout = 30.seconds)
        .props(target = potentiallyFailingService),
      "serviceCircuitBreaker")

  import context.dispatcher

  override def receive: Receive = {
    case AskFor(requestToForward) ⇒
      serviceCircuitBreaker.askWithCircuitBreaker(Request(requestToForward)).mapTo[String].onComplete {
        case Success(successResponse) ⇒
          //handle response
          log.info("Got successful response {}", successResponse)

        case Failure(exception) ⇒
          //handle response
          log.info("Got successful response {}", exception)

      }
  }
}
//#ask-with-circuit-breaker-sample