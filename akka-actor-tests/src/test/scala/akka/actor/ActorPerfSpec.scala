/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import scala.language.postfixOps

import akka.testkit.{ PerformanceTest, ImplicitSender, AkkaSpec }
import java.lang.management.ManagementFactory
import scala.concurrent.duration._
import akka.testkit.SocketUtil
import scala.util.Try

object ActorPerfSpec {

  case class Create(number: Int, props: () ⇒ Props)
  case object Created
  case object IsAlive
  case object Alive
  case class WaitForChildren(number: Int)
  case object Waited

  class EmptyActor extends Actor {
    def receive = {
      case IsAlive ⇒ sender() ! Alive
    }
  }

  class EmptyArgsActor(val foo: Int, val bar: Int) extends Actor {
    def receive = {
      case IsAlive ⇒ sender() ! Alive
    }
  }

  class Driver extends Actor {

    def receive = {
      case IsAlive ⇒
        sender() ! Alive
      case Create(number, propsCreator) ⇒
        for (i ← 1 to number) {
          context.actorOf(propsCreator.apply())
        }
        sender() ! Created
      case WaitForChildren(number) ⇒
        context.children.foreach(_ ! IsAlive)
        context.become(waiting(number, sender()), false)
    }

    def waiting(number: Int, replyTo: ActorRef): Receive = {
      var current = number

      {
        case Alive ⇒
          current -= 1
          if (current == 0) {
            replyTo ! Waited
            context.unbecome()
          }
      }
    }
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ActorPerfSpec extends AkkaSpec("akka.actor.serialize-messages = off") with ImplicitSender {

  import ActorPerfSpec._

  val warmUp: Int = Integer.getInteger("akka.test.actor.ActorPerfSpec.warmUp", 50000)
  val numberOfActors: Int = Integer.getInteger("akka.test.actor.ActorPerfSpec.numberOfActors", 100000)
  val numberOfRepeats: Int = Integer.getInteger("akka.test.actor.ActorPerfSpec.numberOfRepeats", 2)

  def testActorCreation(name: String, propsCreator: () ⇒ Props): Unit = {
    val actorName = name.replaceAll("[ #\\?/!\\*%\\(\\)\\[\\]]", "_")
    if (warmUp > 0)
      measure(s"${actorName}_warmup", warmUp, propsCreator)
    val results = for (i ← 1 to numberOfRepeats) yield measure(s"${actorName}_driver_$i", numberOfActors, propsCreator)
    results.foreach {
      case (duration, memory) ⇒
        val micros = duration.toMicros
        val avgMicros = micros.toDouble / numberOfActors
        val avgMemory = memory.toDouble / numberOfActors
        println(s"$name Created $numberOfActors");
        println(s"In $micros us, avg: ${avgMicros}")
        println(s"Footprint ${memory / 1024} KB, avg: ${avgMemory} B")
    }
  }

  def measure(name: String, number: Int, propsCreator: () ⇒ Props): (Duration, Long) = {
    val memMx = ManagementFactory.getMemoryMXBean()
    val driver = system.actorOf(Props[Driver], name)
    driver ! IsAlive
    expectMsg(Alive)
    System.gc()
    val memBefore = memMx.getHeapMemoryUsage
    val start = System.nanoTime()
    driver ! Create(number, propsCreator)
    expectMsgPF(15 seconds, s"$name waiting for Created") { case Created ⇒ }
    val stop = System.nanoTime()
    val duration = Duration.fromNanos(stop - start)
    driver ! WaitForChildren(number)
    expectMsgPF(15 seconds, s"$name waiting for Waited") { case Waited ⇒ }
    System.gc()
    val memAfter = memMx.getHeapMemoryUsage
    driver ! PoisonPill
    watch(driver)
    expectTerminated(driver, 15.seconds)
    (duration, memAfter.getUsed - memBefore.getUsed)
  }

  "Actor creation with actorFor" must {

    "measure time for Props[EmptyActor] with new Props" taggedAs PerformanceTest in {
      testActorCreation("Props[EmptyActor] new", () ⇒ { Props[EmptyActor] })
    }

    "measure time for Props[EmptyActor] with same Props" taggedAs PerformanceTest in {
      val props = Props[EmptyActor]
      testActorCreation("Props[EmptyActor] same", () ⇒ { props })
    }

    "measure time for Props(new EmptyActor) with new Props" taggedAs PerformanceTest in {
      testActorCreation("Props(new EmptyActor) new", () ⇒ { Props(new EmptyActor) })
    }

    "measure time for Props(new EmptyActor) with same Props" taggedAs PerformanceTest in {
      val props = Props(new EmptyActor)
      testActorCreation("Props(new EmptyActor) same", () ⇒ { props })
    }

    "measure time for Props(classOf[EmptyArgsActor], ...) with new Props" taggedAs PerformanceTest in {
      testActorCreation("Props(classOf[EmptyArgsActor], ...) new", () ⇒ { Props(classOf[EmptyArgsActor], 4711, 1729) })
    }

    "measure time for Props(classOf[EmptyArgsActor], ...) with same Props" taggedAs PerformanceTest in {
      val props = Props(classOf[EmptyArgsActor], 4711, 1729)
      testActorCreation("Props(classOf[EmptyArgsActor], ...) same", () ⇒ { props })
    }

    "measure time for Props(new EmptyArgsActor(...)) with new Props" taggedAs PerformanceTest in {
      testActorCreation("Props(new EmptyArgsActor(...)) new", () ⇒ { Props(new EmptyArgsActor(4711, 1729)) })
    }

    "measure time for Props(new EmptyArgsActor(...)) with same Props" taggedAs PerformanceTest in {
      val props = Props(new EmptyArgsActor(4711, 1729))
      testActorCreation("Props(new EmptyArgsActor(...)) same", () ⇒ { props })
    }
  }
  override def expectedTestDuration = 2 minutes
}
