/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.testkit

import scala.concurrent.Await
import scala.concurrent.duration._

import com.github.ghik.silencer.silent
import com.typesafe.config.ConfigFactory
import language.postfixOps
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import akka.actor._
import akka.actor.DeadLetter
import akka.pattern.ask

@silent
class AkkaSpecSpec extends AnyWordSpec with Matchers {
  // TODO DOTTY, yes I know it's wrong
  implicit val pos: org.scalactic.source.Position = new org.scalactic.source.Position(fileName = "", filePathname = "", lineNumber = 1)


  "An AkkaSpec" must {

    "warn about unhandled messages" in {
      implicit val localSystem = ActorSystem("AkkaSpec0", AkkaSpec.testConf)
      try {
        val a = localSystem.actorOf(Props.empty)
        EventFilter.warning(start = "unhandled message", occurrences = 1).intercept {
          a ! 42
        }
      } finally {
        TestKit.shutdownActorSystem(localSystem)
      }
    }

    "terminate all actors" in {
      // verbose config just for demonstration purposes, please leave in in case of debugging
      import akka.util.ccompat.JavaConverters._
      val conf = Map(
        "akka.actor.debug.lifecycle" -> true,
        "akka.actor.debug.event-stream" -> true,
        "akka.loglevel" -> "DEBUG",
        "akka.stdout-loglevel" -> "DEBUG")
      val localSystem = ActorSystem("AkkaSpec1", ConfigFactory.parseMap(conf.asJava).withFallback(AkkaSpec.testConf))
      var refs = Seq.empty[ActorRef]
      val spec = new AkkaSpec(localSystem) { refs = Seq(testActor, localSystem.actorOf(Props.empty, "name")) }
      refs.foreach(_.isTerminated should not be true)
      TestKit.shutdownActorSystem(localSystem)
      spec.awaitCond(refs.forall(_.isTerminated), 2 seconds)
    }

    "stop correctly when sending PoisonPill to rootGuardian" in {
      val localSystem = ActorSystem("AkkaSpec2", AkkaSpec.testConf)
      new AkkaSpec(localSystem) {}
      val latch = new TestLatch(1)(localSystem)
      localSystem.registerOnTermination(latch.countDown())

      localSystem.actorSelection("/") ! PoisonPill

      Await.ready(latch, 2 seconds)
    }

    "enqueue unread messages from testActor to deadLetters" in {
      val localSystem, otherSystem = ActorSystem("AkkaSpec3", AkkaSpec.testConf)

      try {
        var locker = Seq.empty[DeadLetter]
        implicit val timeout = TestKitExtension(localSystem).DefaultTimeout
        val davyJones = otherSystem.actorOf(Props(new Actor {
          def receive = {
            case m: DeadLetter => locker :+= m
            case "Die!"        => sender() ! "finally gone"; context.stop(self)
          }
        }), "davyJones")

        localSystem.eventStream.subscribe(davyJones, classOf[DeadLetter])

        val probe = new TestProbe(localSystem)
        probe.ref.tell(42, davyJones)
        /*
         * this will ensure that the message is actually received, otherwise it
         * may happen that the system.stop() suspends the testActor before it had
         * a chance to put the message into its private queue
         */
        probe.receiveWhile(1 second) {
          case null =>
        }

        val latch = new TestLatch(1)(localSystem)
        localSystem.registerOnTermination(latch.countDown())
        TestKit.shutdownActorSystem(localSystem)
        Await.ready(latch, 2 seconds)
        Await.result(davyJones ? "Die!", timeout.duration) should ===("finally gone")

        // this will typically also contain log messages which were sent after the logger shutdown
        locker should contain(DeadLetter(42, davyJones, probe.ref))
      } finally {
        TestKit.shutdownActorSystem(localSystem)
        TestKit.shutdownActorSystem(otherSystem)
      }
    }
  }
}
