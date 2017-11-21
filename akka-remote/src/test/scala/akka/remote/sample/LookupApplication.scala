package akka.remote.sample

import scala.concurrent.duration._
import scala.util.Random

import akka.actor.Actor
import akka.actor.ActorIdentity
import akka.actor.ActorRef
import com.typesafe.config.ConfigFactory
import akka.actor.ActorSystem
import akka.actor.Identify
import akka.actor.Props
import akka.actor.ReceiveTimeout
import akka.actor.Terminated

// FIXME remove this sample app when issue #23967 has been tested

object LookupApplication {

  val configCalculator = ConfigFactory.parseString("""
    akka {
      loglevel = DEBUG
      actor {
        provider = remote
      }

      remote.artery {
        enabled = on
        canonical.port = 2552
        canonical.hostname = "127.0.0.1"

        advanced.stop-idle-outbound-after = 20 s

        advanced.compression {
          actor-refs.advertisement-interval = 15s
          manifests.advertisement-interval = 15s
        }
      }
    }
    """)

  val configLookup = ConfigFactory.parseString("""
    akka.remote.artery.canonical.port = 0
    """).withFallback(configCalculator)

  def main(args: Array[String]): Unit = {
    if (args.isEmpty || args.head == "Calculator")
      startRemoteCalculatorSystem()
    if (args.isEmpty || args.head == "Lookup")
      startRemoteLookupSystem()
  }

  def startRemoteCalculatorSystem(): Unit = {
    val system = ActorSystem(
      "CalculatorSystem",
      configCalculator)
    system.actorOf(Props[CalculatorActor], "calculator")

    println("Started CalculatorSystem - waiting for messages")
  }

  def startRemoteLookupSystem(): Unit = {
    val system =
      ActorSystem("LookupSystem", configLookup)
    val remotePath =
      "akka://CalculatorSystem@127.0.0.1:2552/user/calculator"
    val actor = system.actorOf(Props(classOf[LookupActor], remotePath), "lookupActor")

    println("Started LookupSystem")
    import system.dispatcher
    system.scheduler.schedule(1.second, 1.second) {
      if (Random.nextInt(100) % 2 == 0)
        actor ! Add(Random.nextInt(100), Random.nextInt(100))
      else
        actor ! Subtract(Random.nextInt(100), Random.nextInt(100))
    }

    //    system.scheduler.scheduleOnce(25.second) {
    //      system.terminate()
    //    }
  }
}

class LookupActor(path: String) extends Actor {

  sendIdentifyRequest()

  def sendIdentifyRequest(): Unit = {
    context.actorSelection(path) ! Identify(path)
    import context.dispatcher
    context.system.scheduler.scheduleOnce(3.seconds, self, ReceiveTimeout)
  }

  def receive = identifying

  def identifying: Actor.Receive = {
    case ActorIdentity(`path`, Some(actor)) ⇒
      //      context.watch(actor)
      context.become(active(actor))
    case ActorIdentity(`path`, None) ⇒ println(s"Remote actor not available: $path")
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
    case Terminated(`actor`) ⇒
      println("Calculator terminated")
      sendIdentifyRequest()
      context.become(identifying)
    case ReceiveTimeout ⇒
    // ignore

  }
}

class CalculatorActor extends Actor {
  def receive = {
    case Add(n1, n2) ⇒
      println("Calculating %d + %d".format(n1, n2))
      sender() ! AddResult(n1, n2, n1 + n2)
    case Subtract(n1, n2) ⇒
      println("Calculating %d - %d".format(n1, n2))
      sender() ! SubtractResult(n1, n2, n1 - n2)
    case Multiply(n1, n2) ⇒
      println("Calculating %d * %d".format(n1, n2))
      sender() ! MultiplicationResult(n1, n2, n1 * n2)
    case Divide(n1, n2) ⇒
      println("Calculating %.0f / %d".format(n1, n2))
      sender() ! DivisionResult(n1, n2, n1 / n2)
  }
}

trait MathOp

final case class Add(nbr1: Int, nbr2: Int) extends MathOp

final case class Subtract(nbr1: Int, nbr2: Int) extends MathOp

final case class Multiply(nbr1: Int, nbr2: Int) extends MathOp

final case class Divide(nbr1: Double, nbr2: Int) extends MathOp

trait MathResult

final case class AddResult(nbr: Int, nbr2: Int, result: Int) extends MathResult

final case class SubtractResult(nbr1: Int, nbr2: Int, result: Int) extends MathResult

final case class MultiplicationResult(nbr1: Int, nbr2: Int, result: Int) extends MathResult

final case class DivisionResult(nbr1: Double, nbr2: Int, result: Double) extends MathResult

