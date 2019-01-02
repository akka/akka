/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.testkit

import language.postfixOps

import org.scalatest.WordSpec
import org.scalatest.Matchers
import akka.actor._
import com.typesafe.config.ConfigFactory
import scala.concurrent.Await
import scala.concurrent.duration._
import akka.actor.DeadLetter
import akka.pattern.ask

class AkkaSpecSpec extends WordSpec with Matchers {

  "An AkkaSpec" must {

    "warn about unhandled messages" in {
      implicit val system = ActorSystem("AkkaSpec0", AkkaSpec.testConf)
      try {
        val a = system.actorOf(Props.empty)
        EventFilter.warning(start = "unhandled message", occurrences = 1) intercept {
          a ! 42
        }
      } finally {
        TestKit.shutdownActorSystem(system)
      }
    }

    "terminate all actors" in {
      // verbose config just for demonstration purposes, please leave in in case of debugging
      import scala.collection.JavaConverters._
      val conf = Map(
        "akka.actor.debug.lifecycle" → true, "akka.actor.debug.event-stream" → true,
        "akka.loglevel" → "DEBUG", "akka.stdout-loglevel" → "DEBUG")
      val system = ActorSystem("AkkaSpec1", ConfigFactory.parseMap(conf.asJava).withFallback(AkkaSpec.testConf))
      var refs = Seq.empty[ActorRef]
      val spec = new AkkaSpec(system) { refs = Seq(testActor, system.actorOf(Props.empty, "name")) }
      refs foreach (_.isTerminated should not be true)
      TestKit.shutdownActorSystem(system)
      spec.awaitCond(refs forall (_.isTerminated), 2 seconds)
    }

    "stop correctly when sending PoisonPill to rootGuardian" in {
      val system = ActorSystem("AkkaSpec2", AkkaSpec.testConf)
      new AkkaSpec(system) {}
      val latch = new TestLatch(1)(system)
      system.registerOnTermination(latch.countDown())

      system.actorSelection("/") ! PoisonPill

      Await.ready(latch, 2 seconds)
    }

    "enqueue unread messages from testActor to deadLetters" in {
      val system, otherSystem = ActorSystem("AkkaSpec3", AkkaSpec.testConf)

      try {
        var locker = Seq.empty[DeadLetter]
        implicit val timeout = TestKitExtension(system).DefaultTimeout
        val davyJones = otherSystem.actorOf(Props(new Actor {
          def receive = {
            case m: DeadLetter ⇒ locker :+= m
            case "Die!"        ⇒ sender() ! "finally gone"; context.stop(self)
          }
        }), "davyJones")

        system.eventStream.subscribe(davyJones, classOf[DeadLetter])

        val probe = new TestProbe(system)
        probe.ref.tell(42, davyJones)
        /*
       * this will ensure that the message is actually received, otherwise it
       * may happen that the system.stop() suspends the testActor before it had
       * a chance to put the message into its private queue
       */
        probe.receiveWhile(1 second) {
          case null ⇒
        }

        val latch = new TestLatch(1)(system)
        system.registerOnTermination(latch.countDown())
        TestKit.shutdownActorSystem(system)
        Await.ready(latch, 2 seconds)
        Await.result(davyJones ? "Die!", timeout.duration) should ===("finally gone")

        // this will typically also contain log messages which were sent after the logger shutdown
        locker should contain(DeadLetter(42, davyJones, probe.ref))
      } finally {
        TestKit.shutdownActorSystem(system)
        TestKit.shutdownActorSystem(otherSystem)
      }
    }
  }
}
