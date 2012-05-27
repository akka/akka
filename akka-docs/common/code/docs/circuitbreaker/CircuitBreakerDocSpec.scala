/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.circuitbreaker

//#imports1
import akka.util.duration._  // small d is important here
import akka.pattern.CircuitBreaker
import akka.actor.Actor
import akka.dispatch.Future
import akka.event.Logging

//#imports1

//#circuit-breaker-initialization
class DangerousActor extends Actor {

	val log = Logging(context.system, this)
	implicit val executionContext = context.dispatcher
	val breaker = 
		new CircuitBreaker(context.system.scheduler, 5, 10.seconds, 1.minute)
			.onOpen(notifyMeOnOpen)

	def notifyMeOnOpen: = 
		log.warn("My CircuitBreaker is now open, and will not close for one minute")
//#circuit-breaker-initialization

//#circuit-breaker-usage
	def dangerousCall: String = "This really isn't that dangerous of a call after all"

	def receive = {
		case "is my middle name" => 
			sender ! breaker.withCircuitBreaker(Future(dangerousCall()))
		case "block for me" => 
			sender ! breaker.withSyncCircuitBreaker(dangerousCall())
	}
//#circuit-breaker-usage

}

