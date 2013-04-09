/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package sample.remote.calculator

/*
 * comments like //#<tag> are there for inclusion into docs, please don’t remove
 */

import akka.kernel.Bootable
import com.typesafe.config.ConfigFactory
import scala.util.Random
import akka.actor._

class CreationApplication extends Bootable {
  //#setup
  val system =
    ActorSystem("RemoteCreation", ConfigFactory.load.getConfig("remotecreation"))
  val remoteActor = system.actorOf(Props[AdvancedCalculatorActor],
    name = "advancedCalculator")
  val localActor = system.actorOf(Props(new CreationActor(remoteActor)),
    name = "creationActor")

  def doSomething(op: MathOp): Unit =
    localActor ! op
  //#setup

  def startup() {
  }

  def shutdown() {
    system.shutdown()
  }
}

//#actor
class CreationActor(remoteActor: ActorRef) extends Actor {
  def receive = {
    case op: MathOp ⇒ remoteActor ! op
    case result: MathResult ⇒ result match {
      case MultiplicationResult(n1, n2, r) ⇒
        printf("Mul result: %d * %d = %d\n", n1, n2, r)
      case DivisionResult(n1, n2, r) ⇒
        printf("Div result: %.0f / %d = %.2f\n", n1, n2, r)
    }
  }
}
//#actor

object CreationApp {
  def main(args: Array[String]) {
    val app = new CreationApplication
    println("Started Creation Application")
    while (true) {
      if (Random.nextInt(100) % 2 == 0)
        app.doSomething(Multiply(Random.nextInt(20), Random.nextInt(20)))
      else
        app.doSomething(Divide(Random.nextInt(10000), (Random.nextInt(99) + 1)))

      Thread.sleep(200)
    }
  }
}
