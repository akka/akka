/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package sample.remote.calculator

/*
 * comments like //#<tag> are there for inclusion into docs, please don’t remove
 */

import scala.util.Random
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import akka.actor.{ ActorRef, Props, Actor, ActorSystem }
import akka.actor.Identify
import akka.actor.ActorIdentity
import akka.kernel.Bootable
import akka.actor.ReceiveTimeout
//#imports

class LookupApplication extends Bootable {
  //#setup
  val system =
    ActorSystem("LookupApplication", ConfigFactory.load.getConfig("remotelookup"))
  val remotePath =
    "akka.tcp://CalculatorApplication@127.0.0.1:2552/user/simpleCalculator"
  val actor = system.actorOf(Props(new LookupActor(remotePath)), "lookupActor")

  def doSomething(op: MathOp): Unit =
    actor ! op
  //#setup

  def startup() {
  }

  def shutdown() {
    system.shutdown()
  }
}

//#actor
class LookupActor(path: String) extends Actor {

  context.setReceiveTimeout(3.seconds)
  sendIdentifyRequest()

  def sendIdentifyRequest(): Unit =
    context.actorSelection(path) ! Identify(path)

  def receive = {
    case ActorIdentity(`path`, Some(actor)) ⇒
      context.setReceiveTimeout(Duration.Undefined)
      context.become(active(actor))
    case ActorIdentity(`path`, None) ⇒ println(s"Remote actor not availible: $path")
    case ReceiveTimeout              ⇒ sendIdentifyRequest()
    case _                           ⇒ println("Not ready yet")
  }

  def active(actor: ActorRef): Actor.Receive = {
    case op: MathOp ⇒ actor ! op
    case result: MathResult ⇒ result match {
      case AddResult(n1, n2, r) ⇒
        printf("Add result: %d + %d = %d\n", n1, n2, r)
      case SubtractResult(n1, n2, r) ⇒
        printf("Sub result: %d - %d = %d\n", n1, n2, r)
    }
  }
}
//#actor

object LookupApp {
  def main(args: Array[String]) {
    val app = new LookupApplication
    println("Started Lookup Application")
    while (true) {
      if (Random.nextInt(100) % 2 == 0)
        app.doSomething(Add(Random.nextInt(100), Random.nextInt(100)))
      else
        app.doSomething(Subtract(Random.nextInt(100), Random.nextInt(100)))

      Thread.sleep(200)
    }
  }
}
