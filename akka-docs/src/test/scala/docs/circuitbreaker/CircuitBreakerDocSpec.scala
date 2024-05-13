/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.circuitbreaker

//#imports1
import scala.concurrent.duration._
import akka.pattern.CircuitBreaker
import akka.pattern.pipe
import akka.actor.{ Actor, ActorLogging, ActorRef }
import com.typesafe.config.ConfigFactory

import scala.concurrent.Future
import scala.util.{ Failure, Success, Try }

//#imports1

object CircuitBreakerDocSpec {
  def config = ConfigFactory.parseString("""
       #config
       akka.circuit-breaker.dangerous-breaker {
         max-failures = 5
         call-timeout = 10s
         reset-timeout = 1m
       }
       #config
      """)

}

//#circuit-breaker-initialization
class DangerousActor extends Actor with ActorLogging {
  import context.dispatcher
  import context.system

  val breaker = CircuitBreaker("dangerous-breaker").onOpen(notifyMeOnOpen())

  def notifyMeOnOpen(): Unit =
    log.warning("My CircuitBreaker is now open, and will not close for one minute")
  //#circuit-breaker-initialization

  //#circuit-breaker-usage
  def dangerousCall: String = "This really isn't that dangerous of a call after all"

  def receive = {
    case "is my middle name" =>
      breaker.withCircuitBreaker(Future(dangerousCall)).pipeTo(sender())
    case "block for me" =>
      sender() ! breaker.withSyncCircuitBreaker(dangerousCall)
  }
  //#circuit-breaker-usage

}

class TellPatternActor(recipient: ActorRef) extends Actor with ActorLogging {
  import context.dispatcher

  val breaker =
    new CircuitBreaker(context.system.scheduler, maxFailures = 5, callTimeout = 10.seconds, resetTimeout = 1.minute)
      .onOpen(notifyMeOnOpen())

  def notifyMeOnOpen(): Unit =
    log.warning("My CircuitBreaker is now open, and will not close for one minute")

  //#circuit-breaker-tell-pattern
  import akka.actor.ReceiveTimeout

  def receive = {
    case "call" if breaker.isClosed => {
      recipient ! "message"
    }
    case "response" => {
      breaker.succeed()
    }
    case err: Throwable => {
      breaker.fail()
    }
    case ReceiveTimeout => {
      breaker.fail()
    }
  }
  //#circuit-breaker-tell-pattern
}

class EvenNoFailureActor extends Actor {
  import context.dispatcher
  import context.system

  //#even-no-as-failure
  def luckyNumber(): Future[Int] = {
    val evenNumberAsFailure: Try[Int] => Boolean = {
      case Success(n) => n % 2 == 0
      case Failure(_) => true
    }

    val breaker = CircuitBreaker("dangerous-breaker")

    // this call will return 8888 and increase failure count at the same time
    breaker.withCircuitBreaker(Future(8888), evenNumberAsFailure)
  }
  //#even-no-as-failure

  override def receive = {
    case x: Int =>
  }

  def showManualApi(): Unit = {
    // #manual-construction
    val breaker =
      new CircuitBreaker(context.system.scheduler, maxFailures = 5, callTimeout = 10.seconds, resetTimeout = 1.minute)
    // #manual-construction
  }
}
