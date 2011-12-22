/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package sample.remote.calculator

import akka.kernel.Bootable
import com.typesafe.config.ConfigFactory
import scala.util.Random
import akka.actor.{ ActorRef, Props, Actor, ActorSystem }

class LookupApplication extends Bootable {
  val system = ActorSystem("LookupApplication", ConfigFactory.load.getConfig("remotelookup"))
  val actor = system.actorOf(Props[LookupActor], "lookupActor")
  val remoteActor = system.actorFor("akka://CalculatorApplication@127.0.0.1:2552/user/simpleCalculator")

  def doSomething(op: MathOp) = {
    actor ! (remoteActor, op)
  }

  def startup() {
  }

  def shutdown() {
    system.shutdown()
  }
}

class LookupActor extends Actor {
  def receive = {
    case (actor: ActorRef, op: MathOp) ⇒ actor ! op
    case result: MathResult ⇒ result match {
      case AddResult(n1, n2, r)      ⇒ println("Add result: %d + %d = %d".format(n1, n2, r))
      case SubtractResult(n1, n2, r) ⇒ println("Sub result: %d - %d = %d".format(n1, n2, r))
    }
  }
}

object LookupApp {
  def main(args: Array[String]) {
    val app = new LookupApplication
    println("Started Lookup Application")
    while (true) {
      if (Random.nextInt(100) % 2 == 0) app.doSomething(Add(Random.nextInt(100), Random.nextInt(100)))
      else app.doSomething(Subtract(Random.nextInt(100), Random.nextInt(100)))

      Thread.sleep(200)
    }
  }
}
