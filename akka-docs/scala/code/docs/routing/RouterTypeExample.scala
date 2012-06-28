/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.routing

import language.postfixOps

import akka.routing.{ ScatterGatherFirstCompletedRouter, BroadcastRouter, RandomRouter, RoundRobinRouter }
import annotation.tailrec
import akka.actor.{ Props, Actor }
import akka.util.duration._
import akka.util.Timeout
import akka.dispatch.Await
import akka.pattern.ask
import akka.routing.SmallestMailboxRouter

case class FibonacciNumber(nbr: Int)

//#printlnActor
class PrintlnActor extends Actor {
  def receive = {
    case msg ⇒
      println("Received message '%s' in actor %s".format(msg, self.path.name))
  }
}

//#printlnActor

//#fibonacciActor
class FibonacciActor extends Actor {
  def receive = {
    case FibonacciNumber(nbr) ⇒ sender tell fibonacci(nbr)
  }

  private def fibonacci(n: Int): Int = {
    @tailrec
    def fib(n: Int, b: Int, a: Int): Int = n match {
      case 0 ⇒ a
      case _ ⇒ fib(n - 1, a + b, b)
    }

    fib(n, 1, 0)
  }
}

//#fibonacciActor

//#parentActor
class ParentActor extends Actor {
  def receive = {
    case "rrr" ⇒
      //#roundRobinRouter
      val roundRobinRouter =
        context.actorOf(Props[PrintlnActor].withRouter(RoundRobinRouter(5)), "router")
      1 to 10 foreach {
        i ⇒ roundRobinRouter ! i
      }
    //#roundRobinRouter
    case "rr" ⇒
      //#randomRouter
      val randomRouter =
        context.actorOf(Props[PrintlnActor].withRouter(RandomRouter(5)), "router")
      1 to 10 foreach {
        i ⇒ randomRouter ! i
      }
    //#randomRouter
    case "smr" ⇒
      //#smallestMailboxRouter
      val smallestMailboxRouter =
        context.actorOf(Props[PrintlnActor].withRouter(SmallestMailboxRouter(5)), "router")
      1 to 10 foreach {
        i ⇒ smallestMailboxRouter ! i
      }
    //#smallestMailboxRouter
    case "br" ⇒
      //#broadcastRouter
      val broadcastRouter =
        context.actorOf(Props[PrintlnActor].withRouter(BroadcastRouter(5)), "router")
      broadcastRouter ! "this is a broadcast message"
    //#broadcastRouter
    case "sgfcr" ⇒
      //#scatterGatherFirstCompletedRouter
      val scatterGatherFirstCompletedRouter = context.actorOf(
        Props[FibonacciActor].withRouter(ScatterGatherFirstCompletedRouter(
          nrOfInstances = 5, within = 2 seconds)), "router")
      implicit val timeout = Timeout(5 seconds)
      val futureResult = scatterGatherFirstCompletedRouter ? FibonacciNumber(10)
      val result = Await.result(futureResult, timeout.duration)
      //#scatterGatherFirstCompletedRouter
      println("The result of calculating Fibonacci for 10 is %d".format(result))
  }
}

//#parentActor