/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit

import language.{ postfixOps, reflectiveCalls }

import org.scalatest.{ WordSpec, BeforeAndAfterAll, Tag }
import org.scalatest.matchers.MustMatchers
import akka.actor.{ Actor, Props, ActorSystem, PoisonPill, DeadLetter, ActorSystemImpl }
import akka.event.{ Logging, LoggingAdapter }
import scala.concurrent.util.duration._
import scala.concurrent.{ Await, Future }
import com.typesafe.config.{ Config, ConfigFactory }
import java.util.concurrent.TimeoutException
import akka.dispatch.Dispatchers
import akka.pattern.ask

object TimingTest extends Tag("timing")
object LongRunningTest extends Tag("long-running")

object AkkaSpec {
  val testConf: Config = ConfigFactory.parseString("""
      akka {
        event-handlers = ["akka.testkit.TestEventListener"]
        loglevel = "WARNING"
        stdout-loglevel = "WARNING"
        actor {
          default-dispatcher {
            executor = "fork-join-executor"
            fork-join-executor {
              parallelism-min = 8
              parallelism-factor = 2.0
              parallelism-max = 8
            }
          }
        }
      }
      """)

  def mapToConfig(map: Map[String, Any]): Config = {
    import scala.collection.JavaConverters._
    ConfigFactory.parseMap(map.asJava)
  }

  def getCallerName(clazz: Class[_]): String = {
    val s = Thread.currentThread.getStackTrace map (_.getClassName) drop 1 dropWhile (_ matches ".*AkkaSpec.?$")
    val reduced = s.lastIndexWhere(_ == clazz.getName) match {
      case -1 ⇒ s
      case z  ⇒ s drop (z + 1)
    }
    reduced.head.replaceFirst(""".*\.""", "").replaceAll("[^a-zA-Z_0-9]", "_")
  }

}

abstract class AkkaSpec(_system: ActorSystem)
  extends TestKit(_system) with WordSpec with MustMatchers with BeforeAndAfterAll {

  def this(config: Config) = this(ActorSystem(AkkaSpec.getCallerName(getClass),
    ConfigFactory.load(config.withFallback(AkkaSpec.testConf))))

  def this(s: String) = this(ConfigFactory.parseString(s))

  def this(configMap: Map[String, _]) = this(AkkaSpec.mapToConfig(configMap))

  def this() = this(ActorSystem(AkkaSpec.getCallerName(getClass), AkkaSpec.testConf))

  val log: LoggingAdapter = Logging(system, this.getClass)

  final override def beforeAll {
    atStartup()
  }

  final override def afterAll {
    beforeShutdown()
    system.shutdown()
    try system.awaitTermination(5 seconds) catch {
      case _: TimeoutException ⇒
        system.log.warning("Failed to stop [{}] within 5 seconds", system.name)
        println(system.asInstanceOf[ActorSystemImpl].printTree)
    }
    atTermination()
  }

  protected def atStartup() {}

  protected def beforeShutdown() {}

  protected def atTermination() {}

  def spawn(dispatcherId: String = Dispatchers.DefaultDispatcherId)(body: ⇒ Unit): Unit =
    Future(body)(system.dispatchers.lookup(dispatcherId))
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class AkkaSpecSpec extends WordSpec with MustMatchers {

  "An AkkaSpec" must {

    "warn about unhandled messages" in {
      implicit val system = ActorSystem("AkkaSpec0", AkkaSpec.testConf)
      try {
        val a = system.actorOf(Props.empty)
        EventFilter.warning(start = "unhandled message", occurrences = 1) intercept {
          a ! 42
        }
      } finally {
        system.shutdown()
      }
    }

    "terminate all actors" in {
      // verbose config just for demonstration purposes, please leave in in case of debugging
      import scala.collection.JavaConverters._
      val conf = Map(
        "akka.actor.debug.lifecycle" -> true, "akka.actor.debug.event-stream" -> true,
        "akka.loglevel" -> "DEBUG", "akka.stdout-loglevel" -> "DEBUG")
      val system = ActorSystem("AkkaSpec1", ConfigFactory.parseMap(conf.asJava).withFallback(AkkaSpec.testConf))
      val spec = new AkkaSpec(system) { val ref = Seq(testActor, system.actorOf(Props.empty, "name")) }
      spec.ref foreach (_.isTerminated must not be true)
      system.shutdown()
      spec.awaitCond(spec.ref forall (_.isTerminated), 2 seconds)
    }

    "must stop correctly when sending PoisonPill to rootGuardian" in {
      val system = ActorSystem("AkkaSpec2", AkkaSpec.testConf)
      val spec = new AkkaSpec(system) {}
      val latch = new TestLatch(1)(system)
      system.registerOnTermination(latch.countDown())

      system.actorFor("/") ! PoisonPill

      Await.ready(latch, 2 seconds)
    }

    "must enqueue unread messages from testActor to deadLetters" in {
      val system, otherSystem = ActorSystem("AkkaSpec3", AkkaSpec.testConf)

      try {
        var locker = Seq.empty[DeadLetter]
        implicit val timeout = TestKitExtension(system).DefaultTimeout
        implicit val davyJones = otherSystem.actorOf(Props(new Actor {
          def receive = {
            case m: DeadLetter ⇒ locker :+= m
            case "Die!"        ⇒ sender ! "finally gone"; context.stop(self)
          }
        }), "davyJones")

        system.eventStream.subscribe(davyJones, classOf[DeadLetter])

        val probe = new TestProbe(system)
        probe.ref ! 42
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
        system.shutdown()
        Await.ready(latch, 2 seconds)
        Await.result(davyJones ? "Die!", timeout.duration) must be === "finally gone"

        // this will typically also contain log messages which were sent after the logger shutdown
        locker must contain(DeadLetter(42, davyJones, probe.ref))
      } finally {
        system.shutdown()
        otherSystem.shutdown()
      }
    }

  }
}

