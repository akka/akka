/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit

import org.scalatest.{ WordSpec, BeforeAndAfterAll, Tag }
import org.scalatest.matchers.MustMatchers
import akka.actor.{ ActorSystem, ActorSystemImpl }
import akka.actor.{ Actor, ActorRef, Props }
import akka.dispatch.MessageDispatcher
import akka.event.{ Logging, LoggingAdapter }
import akka.util.duration._
import akka.dispatch.FutureTimeoutException
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import akka.actor.PoisonPill
import java.util.concurrent.LinkedBlockingQueue
import akka.actor.CreateChild
import akka.actor.DeadLetter

object TimingTest extends Tag("timing")

object AkkaSpec {
  val testConf = {
    val cfg = ConfigFactory.parseString("""
      akka {
        event-handlers = ["akka.testkit.TestEventListener"]
        loglevel = "WARNING"
        stdout-loglevel = "WARNING"
        actor {
          default-dispatcher {
            core-pool-size-factor = 2
          }
        }
      }
      """)
    ConfigFactory.load(cfg)
  }

  def mapToConfig(map: Map[String, Any]): Config = {
    import scala.collection.JavaConverters._
    ConfigFactory.parseMap(map.asJava)
  }

  def getCallerName: String = {
    val s = Thread.currentThread.getStackTrace map (_.getClassName) drop 1 dropWhile (_ matches ".*AkkaSpec.?$")
    s.head.replaceFirst(""".*\.""", "").replaceAll("[^a-zA-Z_0-9]", "_")
  }

}

abstract class AkkaSpec(_system: ActorSystem)
  extends TestKit(_system) with WordSpec with MustMatchers with BeforeAndAfterAll {

  def this(config: Config) = this(ActorSystem(AkkaSpec.getCallerName, config.withFallback(AkkaSpec.testConf)))

  def this(s: String) = this(ConfigFactory.parseString(s))

  def this(configMap: Map[String, _]) = this(AkkaSpec.mapToConfig(configMap))

  def this() = this(ActorSystem(AkkaSpec.getCallerName, AkkaSpec.testConf))

  val log: LoggingAdapter = Logging(system, this.getClass)

  final override def beforeAll {
    atStartup()
  }

  final override def afterAll {
    system.stop()
    try system.asInstanceOf[ActorSystemImpl].terminationFuture.await(5 seconds) catch {
      case _: FutureTimeoutException ⇒ system.log.warning("Failed to stop [{}] within 5 seconds", system.name)
    }
    atTermination()
  }

  protected def atStartup() {}

  protected def atTermination() {}

  def actorOf(props: Props): ActorRef = system.actorOf(props)

  def actorOf[T <: Actor](clazz: Class[T]): ActorRef = actorOf(Props(clazz))

  def actorOf[T <: Actor: Manifest]: ActorRef = actorOf(manifest[T].erasure.asInstanceOf[Class[_ <: Actor]])

  def actorOf[T <: Actor](factory: ⇒ T): ActorRef = actorOf(Props(factory))

  def spawn(body: ⇒ Unit)(implicit dispatcher: MessageDispatcher) {
    actorOf(Props(ctx ⇒ { case "go" ⇒ try body finally ctx.self.stop() }).withDispatcher(dispatcher)) ! "go"
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class AkkaSpecSpec extends WordSpec with MustMatchers {

  "An AkkaSpec" must {

    "terminate all actors" in {
      // verbose config just for demonstration purposes, please leave in in case of debugging
      import scala.collection.JavaConverters._
      val conf = Map(
        "akka.actor.debug.lifecycle" -> true, "akka.actor.debug.event-stream" -> true,
        "akka.loglevel" -> "DEBUG", "akka.stdout-loglevel" -> "DEBUG")
      val system = ActorSystem("AkkaSpec1", ConfigFactory.parseMap(conf.asJava).withFallback(AkkaSpec.testConf))
      val spec = new AkkaSpec(system) {
        val ref = Seq(testActor, system.actorOf(Props.empty, "name"))
      }
      spec.ref foreach (_.isTerminated must not be true)
      system.stop()
      spec.awaitCond(spec.ref forall (_.isTerminated), 2 seconds)
    }

    "must stop correctly when sending PoisonPill to rootGuardian" in {
      val system = ActorSystem("AkkaSpec2", AkkaSpec.testConf)
      val spec = new AkkaSpec(system) {}
      val latch = new TestLatch(1)(system)
      system.registerOnTermination(latch.countDown())

      system.actorFor("/") ! PoisonPill

      latch.await(2 seconds)
    }

    "must enqueue unread messages from testActor to deadLetters" in {
      val system = ActorSystem("AkkaSpec2", AkkaSpec.testConf)

      var locker = Seq.empty[DeadLetter]
      implicit val timeout = system.settings.ActorTimeout
      implicit val davieJones = (system.actorFor("/") ? CreateChild(Props(new Actor {
        def receive = {
          case m: DeadLetter ⇒ locker :+= m
        }
      }), "davieJones")).as[ActorRef].get

      system.eventStream.subscribe(davieJones, classOf[DeadLetter])

      val probe = new TestProbe(system)
      probe.ref ! 42

      val latch = new TestLatch(1)(system)
      system.registerOnTermination(latch.countDown())
      system.stop()
      latch.await(2 seconds)

      // this will typically also contain log messages which were sent after the logger shutdown
      locker must contain(DeadLetter(42, davieJones, probe.ref))
    }

  }
}

