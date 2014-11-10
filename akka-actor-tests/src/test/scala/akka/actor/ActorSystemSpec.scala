/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import language.postfixOps
import akka.testkit._
import org.scalatest.junit.JUnitSuiteLike
import com.typesafe.config.ConfigFactory
import scala.concurrent.{ ExecutionContext, Await, Future }
import scala.concurrent.duration._
import java.util.concurrent.{ RejectedExecutionException, ConcurrentLinkedQueue }
import akka.util.Timeout
import akka.japi.Util.immutableSeq
import akka.pattern.ask
import akka.dispatch._
import com.typesafe.config.Config
import java.util.concurrent.{ LinkedBlockingQueue, BlockingQueue, TimeUnit }
import akka.util.Switch
import akka.util.Helpers.ConfigOps

class JavaExtensionSpec extends JavaExtension with JUnitSuiteLike

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
        master = sender()
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

  case class FastActor(latch: TestLatch, testActor: ActorRef) extends Actor {
    val ref1 = context.actorOf(Props.empty)
    val ref2 = context.actorFor(ref1.path.toString)
    testActor ! ref2.getClass
    latch.countDown()

    def receive = {
      case _ ⇒
    }
  }

  class SlowDispatcher(_config: Config, _prerequisites: DispatcherPrerequisites) extends MessageDispatcherConfigurator(_config, _prerequisites) {
    private val instance = new Dispatcher(
      this,
      config.getString("id"),
      config.getInt("throughput"),
      config.getNanosDuration("throughput-deadline-time"),
      configureExecutor(),
      config.getMillisDuration("shutdown-timeout")) {
      val doneIt = new Switch
      override protected[akka] def registerForExecution(mbox: Mailbox, hasMessageHint: Boolean, hasSystemMessageHint: Boolean): Boolean = {
        val ret = super.registerForExecution(mbox, hasMessageHint, hasSystemMessageHint)
        doneIt.switchOn {
          TestKit.awaitCond(mbox.actor.actor != null, 1.second)
          mbox.actor.actor match {
            case FastActor(latch, _) ⇒ Await.ready(latch, 1.second)
          }
        }
        ret
      }
    }

    /**
     * Returns the same dispatcher instance for each invocation
     */
    override def dispatcher(): MessageDispatcher = instance
  }

  class TestExecutionContext(testActor: ActorRef, underlying: ExecutionContext) extends ExecutionContext {

    def execute(runnable: Runnable): Unit = {
      testActor ! "called"
      underlying.execute(runnable)
    }

    def reportFailure(t: Throwable): Unit = {
      testActor ! "failed"
      underlying.reportFailure(t)
    }
  }

  val config = s"""
      akka.extensions = ["akka.actor.TestExtension"]
      slow {
        type="${classOf[SlowDispatcher].getName}"
      }"""

}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ActorSystemSpec extends AkkaSpec(ActorSystemSpec.config) with ImplicitSender {

  import ActorSystemSpec.FastActor

  "An ActorSystem" must {

    "use scala.concurrent.Future's InternalCallbackEC" in {
      system.asInstanceOf[ActorSystemImpl].internalCallingThreadExecutionContext.getClass.getName should be("scala.concurrent.Future$InternalCallbackExecutor$")
    }

    "reject invalid names" in {
      for (
        n ← Seq(
          "-hallowelt",
          "_hallowelt",
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
      shutdown(ActorSystem("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"))
    }

    "support extensions" in {
      // TestExtension is configured and should be loaded at startup
      system.hasExtension(TestExtension) should be(true)
      TestExtension(system).system should be(system)
      system.extension(TestExtension).system should be(system)
    }

    "log dead letters" in {
      val sys = ActorSystem("LogDeadLetters", ConfigFactory.parseString("akka.loglevel=INFO").withFallback(AkkaSpec.testConf))
      try {
        val a = sys.actorOf(Props[ActorSystemSpec.Terminater])
        watch(a)
        a ! "run"
        expectTerminated(a)
        EventFilter.info(pattern = "not delivered", occurrences = 1).intercept {
          a ! "boom"
        }(sys)
      } finally shutdown(sys)
    }

    "run termination callbacks in order" in {
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

      immutableSeq(result) should be(expected)
    }

    "awaitTermination after termination callbacks" in {
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
      callbackWasRun should be(true)
    }

    "return isTerminated status correctly" in {
      val system = ActorSystem()
      system.isTerminated should be(false)
      system.shutdown()
      system.awaitTermination(10 seconds)
      system.isTerminated should be(true)
    }

    "throw RejectedExecutionException when shutdown" in {
      val system2 = ActorSystem("AwaitTermination", AkkaSpec.testConf)
      system2.shutdown()
      system2.awaitTermination(10 seconds)

      intercept[RejectedExecutionException] {
        system2.registerOnTermination { println("IF YOU SEE THIS THEN THERE'S A BUG HERE") }
      }.getMessage should be("Must be called prior to system shutdown.")
    }

    "reliably create waves of actors" in {
      import system.dispatcher
      implicit val timeout = Timeout((20 seconds).dilated)
      val waves = for (i ← 1 to 3) yield system.actorOf(Props[ActorSystemSpec.Waves]) ? 50000
      Await.result(Future.sequence(waves), timeout.duration + 5.seconds) should be(Seq("done", "done", "done"))
    }

    "find actors that just have been created" in {
      system.actorOf(Props(new FastActor(TestLatch(), testActor)).withDispatcher("slow"))
      expectMsgType[Class[_]] should be(classOf[LocalActorRef])
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
          failing should not be true // because once failing => always failing (it’s due to shutdown)
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

      created filter (ref ⇒ !ref.isTerminated && !ref.asInstanceOf[ActorRefWithCell].underlying.isInstanceOf[UnstartedCell]) should be(Seq())
    }

    "shut down when /user fails" in {
      implicit val system = ActorSystem("Stop", AkkaSpec.testConf)
      EventFilter[ActorKilledException]() intercept {
        system.actorSelection("/user") ! Kill
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
      t.existenceConfirmed should be(true)
      t.addressTerminated should be(false)
      shutdown(system)
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

    "work with a passed in ExecutionContext" in {
      val ecProbe = TestProbe()
      val ec = new ActorSystemSpec.TestExecutionContext(ecProbe.ref, ExecutionContexts.global())

      val system2 = ActorSystem(name = "default", defaultExecutionContext = Some(ec))

      try {
        val ref = system2.actorOf(Props(new Actor {
          def receive = {
            case "ping" ⇒ sender ! "pong"
          }
        }))

        val probe = TestProbe()

        ref.tell("ping", probe.ref)

        ecProbe.expectMsg(1.second, "called")
        probe.expectMsg(1.second, "pong")
      } finally {
        shutdown(system2)
      }
    }

    "not use passed in ExecutionContext if executor is configured" in {
      val ecProbe = TestProbe()
      val ec = new ActorSystemSpec.TestExecutionContext(ecProbe.ref, ExecutionContexts.global())

      val config = ConfigFactory.parseString("akka.actor.default-dispatcher.executor = \"fork-join-executor\"")
      val system2 = ActorSystem(name = "default", config = Some(config), defaultExecutionContext = Some(ec))

      try {
        val ref = system2.actorOf(Props(new Actor {
          def receive = {
            case "ping" ⇒ sender ! "pong"
          }
        }))

        val probe = TestProbe()

        ref.tell("ping", probe.ref)

        ecProbe.expectNoMsg()
        probe.expectMsg(1.second, "pong")
      } finally {
        shutdown(system2)
      }
    }
  }

}
