/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor

import language.postfixOps
import akka.testkit._
import com.typesafe.config.ConfigFactory

import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.concurrent.duration._
import java.util.concurrent.{ ConcurrentLinkedQueue, RejectedExecutionException }

import akka.actor.setup.ActorSystemSetup
import akka.util.Timeout
import akka.japi.Util.immutableSeq
import akka.pattern.ask
import akka.dispatch._
import com.typesafe.config.Config
import akka.util.Switch
import akka.util.Helpers.ConfigOps

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

  final case class FastActor(latch: TestLatch, testActor: ActorRef) extends Actor {
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
      slow {
        type="${classOf[SlowDispatcher].getName}"
      }"""

}

class ActorSystemSpec extends AkkaSpec(ActorSystemSpec.config) with ImplicitSender {

  import ActorSystemSpec.FastActor

  "An ActorSystem" must {

    "use scala.concurrent.Future's InternalCallbackEC" in {
      system.asInstanceOf[ActorSystemImpl].internalCallingThreadExecutionContext.getClass.getName should ===("scala.concurrent.Future$InternalCallbackExecutor$")
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

      system2.terminate()
      Await.ready(latch, 5 seconds)

      val expected = (for (i ← 1 to count) yield i).reverse

      immutableSeq(result) should ===(expected)
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
      system2.scheduler.scheduleOnce(200.millis.dilated) { system2.terminate() }

      system2.awaitTermination(5 seconds)
      Await.ready(system2.whenTerminated, 5 seconds)
      callbackWasRun should ===(true)
    }

    "return isTerminated status correctly" in {
      val system = ActorSystem().asInstanceOf[ActorSystemImpl]
      system.isTerminated should ===(false)
      val wt = system.whenTerminated
      wt.isCompleted should ===(false)
      val f = system.terminate()
      val terminated = Await.result(wt, 10 seconds)
      terminated.actor should ===(system.provider.rootGuardian)
      terminated.addressTerminated should ===(true)
      terminated.existenceConfirmed should ===(true)
      terminated should be theSameInstanceAs Await.result(f, 10 seconds)
      system.awaitTermination(10 seconds)
      system.isTerminated should ===(true)
    }

    "throw RejectedExecutionException when shutdown" in {
      val system2 = ActorSystem("AwaitTermination", AkkaSpec.testConf)
      Await.ready(system2.terminate(), 10 seconds)
      system2.awaitTermination(10 seconds)

      intercept[RejectedExecutionException] {
        system2.registerOnTermination { println("IF YOU SEE THIS THEN THERE'S A BUG HERE") }
      }.getMessage should ===("ActorSystem already terminated.")
    }

    "reliably create waves of actors" in {
      import system.dispatcher
      implicit val timeout = Timeout((20 seconds).dilated)
      val waves = for (i ← 1 to 3) yield system.actorOf(Props[ActorSystemSpec.Waves]) ? 50000
      Await.result(Future.sequence(waves), timeout.duration + 5.seconds) should ===(Vector("done", "done", "done"))
    }

    "find actors that just have been created" in {
      system.actorOf(Props(new FastActor(TestLatch(), testActor)).withDispatcher("slow"))
      expectMsgType[Class[_]] should ===(classOf[LocalActorRef])
    }

    "reliable deny creation of actors while shutting down" in {
      val system = ActorSystem()
      import system.dispatcher
      system.scheduler.scheduleOnce(100 millis) { system.terminate() }
      var failing = false
      var created = Vector.empty[ActorRef]
      while (!system.whenTerminated.isCompleted) {
        try {
          val t = system.actorOf(Props[ActorSystemSpec.Terminater])
          failing should not be true // because once failing => always failing (it’s due to shutdown)
          created :+= t
          if (created.size % 1000 == 0) Thread.sleep(50) // in case of unfair thread scheduling
        } catch {
          case _: IllegalStateException ⇒ failing = true
        }

        if (!failing && system.uptime >= 10) {
          println(created.last)
          println(system.asInstanceOf[ExtendedActorSystem].printTree)
          fail("System didn't terminate within 5 seconds")
        }
      }

      created filter (ref ⇒ !ref.isTerminated && !ref.asInstanceOf[ActorRefWithCell].underlying.isInstanceOf[UnstartedCell]) should ===(Seq.empty[ActorRef])
    }

    "shut down when /user fails" in {
      implicit val system = ActorSystem("Stop", AkkaSpec.testConf)
      EventFilter[ActorKilledException]() intercept {
        system.actorSelection("/user") ! Kill
        Await.ready(system.whenTerminated, Duration.Inf)
      }
    }

    "allow configuration of guardian supervisor strategy" in {
      implicit val system = ActorSystem(
        "Stop",
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
      t.existenceConfirmed should ===(true)
      t.addressTerminated should ===(false)
      shutdown(system)
    }

    "shut down when /user escalates" in {
      implicit val system = ActorSystem(
        "Stop",
        ConfigFactory.parseString("akka.actor.guardian-supervisor-strategy=\"akka.actor.ActorSystemSpec$Strategy\"")
          .withFallback(AkkaSpec.testConf))
      val a = system.actorOf(Props(new Actor {
        def receive = {
          case "die" ⇒ throw new Exception("hello")
        }
      }))
      EventFilter[Exception]("hello") intercept {
        a ! "die"
        Await.ready(system.whenTerminated, Duration.Inf)
      }
    }

    "work with a passed in ExecutionContext" in {
      val ecProbe = TestProbe()
      val ec = new ActorSystemSpec.TestExecutionContext(ecProbe.ref, ExecutionContexts.global())

      val system2 = ActorSystem(name = "default", defaultExecutionContext = Some(ec))

      try {
        val ref = system2.actorOf(Props(new Actor {
          def receive = {
            case "ping" ⇒ sender() ! "pong"
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
        val ref = system2.actorOf(TestActors.echoActorProps)
        val probe = TestProbe()

        ref.tell("ping", probe.ref)

        ecProbe.expectNoMsg(200.millis)
        probe.expectMsg(1.second, "ping")
      } finally {
        shutdown(system2)
      }
    }

    "not allow top-level actor creation with custom guardian" in {
      val sys = new ActorSystemImpl("custom", ConfigFactory.defaultReference(),
        getClass.getClassLoader, None, Some(Props.empty), ActorSystemSetup.empty)
      sys.start()
      try {
        intercept[UnsupportedOperationException] {
          sys.actorOf(Props.empty)
        }
        intercept[UnsupportedOperationException] {
          sys.actorOf(Props.empty, "empty")
        }
      } finally shutdown(sys)
    }
  }

}
