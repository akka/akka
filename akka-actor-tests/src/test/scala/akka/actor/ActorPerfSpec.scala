/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import scala.language.postfixOps

import akka.testkit.{ PerformanceTest, ImplicitSender, AkkaSpec }
import scala.concurrent.duration._
import akka.TestUtils
import akka.testkit.metrics.MetricsKit
import org.scalatest.BeforeAndAfterAll

object ActorPerfSpec {

  final case class Create(number: Int, props: () ⇒ Props)
  case object Created
  case object IsAlive
  case object Alive
  final case class WaitForChildren(number: Int)
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

  class Driver(metrics: MetricsKit, scenarioName: String) extends Actor {

    val timer = metrics.timer("actor-creation." + scenarioName)

    def receive = {
      case IsAlive ⇒
        sender() ! Alive
      case Create(number, propsCreator) ⇒
        for (i ← 1 to number) {
          val t = timer.time()
          context.actorOf(propsCreator.apply())
          t.stop()
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

// todo replace with repeated runner
@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ActorPerfSpec extends AkkaSpec("akka.actor.serialize-messages = off") with ImplicitSender
  with MetricsKit with BeforeAndAfterAll {

  import ActorPerfSpec._

  def metricsConfig = system.settings.config

  val warmUp: Int = Integer.getInteger("akka.test.actor.ActorPerfSpec.warmUp", 50000)
  val numberOfActors: Int = Integer.getInteger("akka.test.actor.ActorPerfSpec.numberOfActors", 100000)
  val numberOfRepeats: Int = Integer.getInteger("akka.test.actor.ActorPerfSpec.numberOfRepeats", 2)

  def testActorCreation(name: String, propsCreator: () ⇒ Props): Unit = {
    val scenarioName = name.replaceAll("""[^\w]""", "")
    if (warmUp > 0)
      measure(s"${scenarioName}_warmup", warmUp, propsCreator)

    measureMemoryUse(s"actor-creation.$scenarioName.mem")
    for (i ← 1 to numberOfRepeats)
      measure(s"${scenarioName}_driver_$i", numberOfActors, propsCreator)

    reportAllMetrics()
    removeMetrics()
  }

  def measure(scenarioName: String, number: Int, propsCreator: () ⇒ Props): (Duration, Long) = {
    val driver = system.actorOf(Props(classOf[Driver], this, scenarioName), scenarioName)
    driver ! IsAlive
    expectMsg(Alive)
    gc()

    driver ! Create(number, propsCreator)
    expectMsgPF(15 seconds, s"$scenarioName waiting for Created") { case Created ⇒ }

    driver ! WaitForChildren(number)
    expectMsgPF(15 seconds, s"$scenarioName waiting for Waited") { case Waited ⇒ }
    gc()
    driver ! PoisonPill
    TestUtils.verifyActorTermination(driver, 15 seconds)

    (0.seconds, 0)
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

  override def afterTermination() = shutdownMetrics()

  override def expectedTestDuration = 2 minutes
}
