/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import language.postfixOps
import akka.testkit._
import org.scalatest.junit.JUnitSuite
import com.typesafe.config.ConfigFactory
import scala.concurrent.Await
import scala.concurrent.util.duration._
import scala.collection.JavaConverters
import java.util.concurrent.{ TimeUnit, RejectedExecutionException, CountDownLatch, ConcurrentLinkedQueue }
import akka.util.Timeout
import scala.concurrent.Future
import akka.pattern.ask

class JavaExtensionSpec extends JavaExtension with JUnitSuite

object TestExtension extends ExtensionId[TestExtension] with ExtensionIdProvider {
  def lookup = this
  def createExtension(s: ExtendedActorSystem) = new TestExtension(s)
}

// Dont't place inside ActorSystemSpec object, since it will not be garbage collected and reference to system remains
class TestExtension(val system: ExtendedActorSystem) extends Extension

object ActorSystemSpec {

  class Waves extends Actor {
    var master: ActorRef = _
    var terminaters = Set[ActorRef]()

    def receive = {
      case n: Int ⇒
        master = sender
        terminaters = Set() ++ (for (i ← 1 to n) yield {
          val man = context.watch(context.system.actorOf(Props[Terminater]))
          man ! "run"
          man
        })
      case Terminated(child) if terminaters contains child ⇒
        terminaters -= child
        if (terminaters.isEmpty) {
          master ! "done"
          context stop self
        }
    }

    override def preRestart(cause: Throwable, msg: Option[Any]) {
      if (master ne null) {
        master ! "failed with " + cause + " while processing " + msg
      }
      context stop self
    }
  }

  class Terminater extends Actor {
    def receive = {
      case "run" ⇒ context.stop(self)
    }
  }

  class Strategy extends SupervisorStrategyConfigurator {
    def create() = OneForOneStrategy() {
      case _ ⇒ SupervisorStrategy.Escalate
    }
  }

}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ActorSystemSpec extends AkkaSpec("""akka.extensions = ["akka.actor.TestExtension"]""") with ImplicitSender {

  "An ActorSystem" must {

    "reject invalid names" in {
      for (
        n ← Seq(
          "hallo_welt",
          "-hallowelt",
          "hallo*welt",
          "hallo@welt",
          "hallo#welt",
          "hallo$welt",
          "hallo%welt",
          "hallo/welt")
      ) intercept[IllegalArgumentException] {
        ActorSystem(n)
      }
    }

    "allow valid names" in {
      ActorSystem("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-").shutdown()
    }

    "support extensions" in {
      // TestExtension is configured and should be loaded at startup
      system.hasExtension(TestExtension) must be(true)
      TestExtension(system).system must be === system
      system.extension(TestExtension).system must be === system
    }

    "run termination callbacks in order" in {
      import scala.collection.JavaConverters._

      val system2 = ActorSystem("TerminationCallbacks", AkkaSpec.testConf)
      val result = new ConcurrentLinkedQueue[Int]
      val count = 10
      val latch = TestLatch(count)

      for (i ← 1 to count) {
        system2.registerOnTermination {
          Thread.sleep((i % 3).millis.dilated.toMillis)
          result add i
          latch.countDown()
        }
      }

      system2.shutdown()
      Await.ready(latch, 5 seconds)

      val expected = (for (i ← 1 to count) yield i).reverse
      result.asScala.toSeq must be(expected)

    }

    "awaitTermination after termination callbacks" in {
      import scala.collection.JavaConverters._

      val system2 = ActorSystem("AwaitTermination", AkkaSpec.testConf)
      @volatile
      var callbackWasRun = false

      system2.registerOnTermination {
        Thread.sleep(50.millis.dilated.toMillis)
        callbackWasRun = true
      }
      import system.dispatcher
      system2.scheduler.scheduleOnce(200.millis.dilated) { system2.shutdown() }

      system2.awaitTermination(5 seconds)
      callbackWasRun must be(true)
    }

    "return isTerminated status correctly" in {
      val system = ActorSystem()
      system.isTerminated must be(false)
      system.shutdown()
      system.awaitTermination()
      system.isTerminated must be(true)
    }

    "throw RejectedExecutionException when shutdown" in {
      val system2 = ActorSystem("AwaitTermination", AkkaSpec.testConf)
      system2.shutdown()
      system2.awaitTermination(5 seconds)

      intercept[RejectedExecutionException] {
        system2.registerOnTermination { println("IF YOU SEE THIS THEN THERE'S A BUG HERE") }
      }.getMessage must be("Must be called prior to system shutdown.")
    }

    "reliably create waves of actors" in {
      import system.dispatcher
      implicit val timeout = Timeout(30 seconds)
      val waves = for (i ← 1 to 3) yield system.actorOf(Props[ActorSystemSpec.Waves]) ? 50000
      Await.result(Future.sequence(waves), timeout.duration + 5.seconds) must be === Seq("done", "done", "done")
    }

    "reliable deny creation of actors while shutting down" in {
      val system = ActorSystem()
      import system.dispatcher
      system.scheduler.scheduleOnce(200 millis) { system.shutdown() }
      var failing = false
      var created = Vector.empty[ActorRef]
      while (!system.isTerminated) {
        try {
          val t = system.actorOf(Props[ActorSystemSpec.Terminater])
          failing must not be true // because once failing => always failing (it’s due to shutdown)
          created :+= t
        } catch {
          case _: IllegalStateException ⇒ failing = true
        }

        if (!failing && system.uptime >= 5) {
          println(created.last)
          println(system.asInstanceOf[ExtendedActorSystem].printTree)
          fail("System didn't terminate within 5 seconds")
        }
      }

      created filter (ref ⇒ !ref.isTerminated && !ref.asInstanceOf[ActorRefWithCell].underlying.isInstanceOf[UnstartedCell]) must be(Seq())
    }

    "shut down when /user fails" in {
      implicit val system = ActorSystem("Stop", AkkaSpec.testConf)
      EventFilter[ActorKilledException]() intercept {
        system.actorFor("/user") ! Kill
        awaitCond(system.isTerminated)
      }
    }

    "allow configuration of guardian supervisor strategy" in {
      implicit val system = ActorSystem("Stop",
        ConfigFactory.parseString("akka.actor.guardian-supervisor-strategy=akka.actor.StoppingSupervisorStrategy")
          .withFallback(AkkaSpec.testConf))
      val a = system.actorOf(Props(new Actor {
        def receive = {
          case "die" ⇒ throw new Exception("hello")
        }
      }))
      val probe = TestProbe()
      probe.watch(a)
      EventFilter[Exception]("hello", occurrences = 1) intercept {
        a ! "die"
      }
      val t = probe.expectMsg(Terminated(a)(existenceConfirmed = true, addressTerminated = false))
      t.existenceConfirmed must be(true)
      t.addressTerminated must be(false)
    }

    "shut down when /user escalates" in {
      implicit val system = ActorSystem("Stop",
        ConfigFactory.parseString("akka.actor.guardian-supervisor-strategy=\"akka.actor.ActorSystemSpec$Strategy\"")
          .withFallback(AkkaSpec.testConf))
      val a = system.actorOf(Props(new Actor {
        def receive = {
          case "die" ⇒ throw new Exception("hello")
        }
      }))
      EventFilter[Exception]("hello") intercept {
        a ! "die"
        awaitCond(system.isTerminated)
      }
    }

  }

}
