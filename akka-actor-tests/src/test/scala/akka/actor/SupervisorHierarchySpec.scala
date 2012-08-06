/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import language.postfixOps

import java.util.concurrent.{ TimeUnit, CountDownLatch }

import scala.concurrent.Await
import scala.concurrent.util.Duration
import scala.concurrent.util.duration.intToDurationInt
import scala.math.BigInt.int2bigInt
import scala.util.Random
import scala.util.control.NoStackTrace

import com.typesafe.config.{ ConfigFactory, Config }

import SupervisorStrategy.{ Resume, Restart, Directive }
import akka.actor.SupervisorStrategy.seqThrowable2Decider
import akka.dispatch.{ MessageDispatcher, DispatcherPrerequisites, DispatcherConfigurator, Dispatcher }
import akka.pattern.ask
import akka.testkit.{ ImplicitSender, EventFilter, DefaultTimeout, AkkaSpec }
import akka.testkit.{ filterException, duration2TestDuration, TestLatch }
import akka.testkit.TestEvent.Mute

object SupervisorHierarchySpec {
  class FireWorkerException(msg: String) extends Exception(msg)

  /**
   * For testing Supervisor behavior, normally you don't supply the strategy
   * from the outside like this.
   */
  class CountDownActor(countDown: CountDownLatch, override val supervisorStrategy: SupervisorStrategy) extends Actor {

    def receive = {
      case p: Props ⇒ sender ! context.actorOf(p)
    }
    // test relies on keeping children around during restart
    override def preRestart(cause: Throwable, msg: Option[Any]) {}
    override def postRestart(reason: Throwable) = {
      countDown.countDown()
    }
  }

  class Resumer extends Actor {
    override def supervisorStrategy = OneForOneStrategy() { case _ ⇒ SupervisorStrategy.Resume }
    def receive = {
      case "spawn" ⇒ sender ! context.actorOf(Props[Resumer])
      case "fail"  ⇒ throw new Exception("expected")
      case "ping"  ⇒ sender ! "pong"
    }
  }

  case class Event(msg: Any) { val time: Long = System.nanoTime }
  case class ErrorLog(msg: String, log: Vector[Event])
  case class Failure(directive: Directive, log: Vector[Event]) extends RuntimeException with NoStackTrace {
    override def toString = "Failure(" + directive + ")"
  }
  val strategy = OneForOneStrategy() { case Failure(directive, _) ⇒ directive }

  val config = ConfigFactory.parseString("""
    hierarchy {
      type = "akka.actor.SupervisorHierarchySpec$MyDispatcherConfigurator"
    }
    akka.loglevel = INFO
    akka.actor.debug.fsm = on
  """)

  class MyDispatcherConfigurator(config: Config, prerequisites: DispatcherPrerequisites)
    extends DispatcherConfigurator(config, prerequisites) {

    private val instance: MessageDispatcher =
      new Dispatcher(prerequisites,
        config.getString("id"),
        config.getInt("throughput"),
        Duration(config.getNanoseconds("throughput-deadline-time"), TimeUnit.NANOSECONDS),
        mailboxType,
        configureExecutor(),
        Duration(config.getMilliseconds("shutdown-timeout"), TimeUnit.MILLISECONDS)) {

        override def suspend(cell: ActorCell): Unit = {
          val a = cell.actor.asInstanceOf[Hierarchy]
          a.log :+= Event("suspended")
          super.suspend(cell)
        }

        override def resume(cell: ActorCell): Unit = {
          val a = cell.actor.asInstanceOf[Hierarchy]
          a.log :+= Event("resumed")
          super.resume(cell)
        }

      }

    override def dispatcher(): MessageDispatcher = instance
  }

  class Hierarchy(depth: Int, breadth: Int, listener: ActorRef) extends Actor {

    override def preStart {
      if (depth > 1)
        for (_ ← 1 to breadth)
          context.watch(context.actorOf(Props(new Hierarchy(depth - 1, breadth, listener)).withDispatcher("hierarchy")))
      listener ! self
    }
    override def postRestart(cause: Throwable) {
      cause match {
        case Failure(_, l) ⇒ log = l
      }
      log :+= Event("restarted")
    }

    override def supervisorStrategy = strategy
    override def preRestart(cause: Throwable, msg: Option[Any]): Unit = {
      // do not scrap children
    }

    override def postStop {
      if (failed || suspended) {
        listener ! ErrorLog("not resumed (" + failed + ", " + suspended + ")", log)
      }
    }

    var failed = false
    var suspended = false
    var log = Vector.empty[Event]
    def check(msg: Any) = {
      suspended = false
      log :+= Event(msg)
      if (failed) {
        listener ! ErrorLog("processing message while failed", log)
        failed = false
        context stop self
      }
    }

    def receive = new Receive {
      val handler: Receive = {
        case f @ Failure(Resume, _) ⇒ suspended = true; throw f.copy(log = log)
        case f: Failure             ⇒ failed = true; throw f.copy(log = log)
        case "ping"                 ⇒ Thread.sleep((Random.nextFloat * 1.03).toLong); sender ! "pong"
        case Terminated(_)          ⇒ listener ! ErrorLog("terminating", log); context stop self
      }
      override def isDefinedAt(msg: Any) = handler.isDefinedAt(msg)
      override def apply(msg: Any) = { check(msg); handler(msg) }
    }
  }

  case class Work(n: Int)

  sealed trait Action
  case class Ping(ref: ActorRef) extends Action
  case class Fail(ref: ActorRef, directive: Directive) extends Action

  sealed trait State
  case object Idle extends State
  case object Init extends State
  case object Stress extends State
  case object Finishing extends State
  case object LastPing extends State
  case object Stopping extends State
  case object Failed extends State

  /*
   * This stress test will construct a supervision hierarchy of configurable
   * depth and breadth and then randomly fail and check its actors. The actors
   * perform certain checks internally (verifying that they do not run when
   * suspended, for example), and they are checked for health by the test 
   * procedure.
   * 
   * Execution happens in phases (which is the reason for FSM):
   * 
   * Idle:
   * - upon reception of Init message, construct hierary and go to Init state
   * 
   * Init:
   * - receive refs of all contained actors
   * 
   * Stress:
   * - deal out actions (Fail or "ping"), keeping the hierarchy busy
   * - whenever all actors are in the "pinged" list (i.e. have not yet
   *   answered with a "pong"), delay processing of the next Work() by
   *   100 millis
   * - when receiving a Work() while all actors are "pinged", stop the
   *   hierarchy and go to the Stopping state
   * 
   * Finishing:
   * - after dealing out the last action, wait for the outstanding "pong"
   *   messages
   * - when last "pong" is received, goto LastPing state
   * - upon state timeout, stop the hierarchy and go to the Failed state
   * 
   * LastPing:
   * - upon entering this state, send a "ping" to all actors
   * - when last "pong" is received, goto Stopping state
   * - upon state timeout, stop the hierarchy and go to the Failed state
   * 
   * Stopping:
   * - upon entering this state, stop the hierarchy
   * - upon termination of the hierarchy send back successful result
   * 
   * Whenever an ErrorLog is received, goto Failed state
   * 
   * Failed:
   * - accumulate ErrorLog messages
   * - upon termination of the hierarchy send back failed result and print
   *   the logs, merged and in chronological order.
   * 
   * TODO RK: also test Stop directive, and keep a complete list of all 
   * actors ever created, then verify after stop()ping the hierarchy that
   * all are terminated, transfer them to a WeakHashMap and verify that 
   * they are indeed GCed
   * 
   * TODO RK: make hierarchy construction stochastic so that it includes
   * different breadth (including the degenerate breadth-1 case).
   * 
   * TODO RK: also test Escalate by adding an exception with a `var depth`
   * which gets decremented within the supervisor and gets handled when zero
   * is reached (Restart resolution)
   * 
   * TODO RK: also test exceptions during recreate
   * 
   * TODO RK: also test recreate including terminating children
   * 
   * TODO RK: also verify that preRestart is not called more than once per instance
   */

  class StressTest(testActor: ActorRef, depth: Int, breadth: Int) extends Actor with LoggingFSM[State, Null] {
    import context.system

    override def supervisorStrategy = strategy

    var children = Vector.empty[ActorRef]
    var idleChildren = Vector.empty[ActorRef]
    var pingChildren = Set.empty[ActorRef]

    val nextJob = Iterator.continually(Random.nextFloat match {
      case x if x >= 0.5 ⇒
        // ping one child
        val pick = ((x - 0.5) * 2 * idleChildren.size).toInt
        val ref = idleChildren(pick)
        idleChildren = idleChildren.take(pick) ++ idleChildren.drop(pick + 1)
        pingChildren += ref
        Ping(ref)
      case x ⇒
        // fail one child
        val pick = ((if (x >= 0.25) x - 0.25 else x) * 4 * children.size).toInt
        Fail(children(pick), if (x > 0.25) Restart else Resume)
    })

    val familySize = ((1 - BigInt(breadth).pow(depth)) / (1 - breadth)).toInt
    var hierarchy: ActorRef = _

    override def preRestart(cause: Throwable, msg: Option[Any]) {
      throw new ActorKilledException("I want to DIE")
    }

    override def postRestart(cause: Throwable) {
      throw new ActorKilledException("I said I wanted to DIE, dammit!")
    }

    override def postStop {
      testActor ! "stressTestStopped"
    }

    startWith(Idle, null)

    when(Idle) {
      case Event(Init, _) ⇒
        hierarchy = context.watch(context.actorOf(Props(new Hierarchy(depth, breadth, self)).withDispatcher("hierarchy")))
        setTimer("phase", StateTimeout, 5 seconds, false)
        goto(Init)
    }

    when(Init) {
      case Event(ref: ActorRef, _) ⇒
        if (idleChildren.nonEmpty || pingChildren.nonEmpty)
          throw new IllegalStateException("received unexpected child " + children.size)
        children :+= ref
        if (children.size == familySize) {
          idleChildren = children
          goto(Stress)
        } else stay
      case Event(StateTimeout, _) ⇒
        testActor ! "only got %d out of %d refs".format(children.size, familySize)
        stop()
    }

    onTransition {
      case Init -> Stress ⇒
        self ! Work(familySize * 1000)
        // set timeout for completion of the whole test (i.e. including Finishing and Stopping)
        setTimer("phase", StateTimeout, 30.seconds.dilated, false)
    }

    val workSchedule = 250.millis

    when(Stress) {
      case Event(w: Work, _) if idleChildren.isEmpty ⇒
        context stop hierarchy
        goto(Failed)
      case Event(Work(x), _) if x > 0 ⇒
        nextJob.next match {
          case Ping(ref)      ⇒ ref ! "ping"
          case Fail(ref, dir) ⇒ ref ! Failure(dir, Vector.empty)
        }
        if (idleChildren.nonEmpty) self ! Work(x - 1)
        else context.system.scheduler.scheduleOnce(workSchedule, self, Work(x - 1))
        stay
      case Event(Work(_), _) ⇒ if (pingChildren.isEmpty) goto(LastPing) else goto(Finishing)
      case Event("pong", _) ⇒
        pingChildren -= sender
        idleChildren :+= sender
        stay
    }

    when(Finishing) {
      case Event("pong", _) ⇒
        pingChildren -= sender
        idleChildren :+= sender
        if (pingChildren.isEmpty) goto(LastPing) else stay
    }

    onTransition {
      case _ -> LastPing ⇒
        idleChildren foreach (_ ! "ping")
        pingChildren ++= idleChildren
        idleChildren = Vector.empty
    }

    when(LastPing) {
      case Event("pong", _) ⇒
        pingChildren -= sender
        idleChildren :+= sender
        if (pingChildren.isEmpty) goto(Stopping) else stay
    }

    onTransition {
      case _ -> Stopping ⇒ context stop hierarchy
    }

    when(Stopping, stateTimeout = 5 seconds) {
      case Event(Terminated(r), _) if r == hierarchy ⇒
        testActor ! "stressTestSuccessful"
        stop
      case Event(StateTimeout, _) ⇒
        testActor ! "timeout in Stopping"
        stop
    }

    var errors = Vector.empty[(ActorRef, ErrorLog)]

    when(Failed, stateTimeout = 5 seconds) {
      case Event(e: ErrorLog, _) ⇒
        errors :+= sender -> e
        stay
      case Event(Terminated(r), _) if r == hierarchy ⇒
        printErrors()
        testActor ! "stressTestFailed"
        stop
      case Event(StateTimeout, _) ⇒
        printErrors()
        testActor ! "timeout in Failed"
        stop
      case Event("pong", _) ⇒ stay // don’t care?
    }

    def printErrors(): Unit = {
      val merged = errors flatMap {
        case (ref, ErrorLog(msg, log)) ⇒
          println("Error: " + ref + " " + msg)
          log map (l ⇒ (l.time, ref, l.msg.toString))
      }
      merged.sorted foreach println
    }

    whenUnhandled {
      case Event(e: ErrorLog, _) ⇒
        errors :+= sender -> e
        // don’t stop the hierarchy, that is going to happen all by itself and in the right order
        goto(Failed)
      case Event(StateTimeout, _) ⇒
        println("pingChildren:\n" + pingChildren.mkString("\n"))
        context stop hierarchy
        goto(Failed)
      case Event(msg, _) ⇒
        testActor ! ("received unexpected msg: " + msg)
        stop
    }

    initialize

  }

}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class SupervisorHierarchySpec extends AkkaSpec(SupervisorHierarchySpec.config) with DefaultTimeout with ImplicitSender {
  import SupervisorHierarchySpec._

  "A Supervisor Hierarchy" must {

    "restart manager and workers in AllForOne" in {
      val countDown = new CountDownLatch(4)

      val boss = system.actorOf(Props(new Supervisor(OneForOneStrategy()(List(classOf[Exception])))))

      val managerProps = Props(new CountDownActor(countDown, AllForOneStrategy()(List())))
      val manager = Await.result((boss ? managerProps).mapTo[ActorRef], timeout.duration)

      val workerProps = Props(new CountDownActor(countDown, SupervisorStrategy.defaultStrategy))
      val workerOne, workerTwo, workerThree = Await.result((manager ? workerProps).mapTo[ActorRef], timeout.duration)

      filterException[ActorKilledException] {
        workerOne ! Kill

        // manager + all workers should be restarted by only killing a worker
        // manager doesn't trap exits, so boss will restart manager

        assert(countDown.await(2, TimeUnit.SECONDS))
      }
    }

    "send notification to supervisor when permanent failure" in {
      val countDownMessages = new CountDownLatch(1)
      val countDownMax = new CountDownLatch(1)
      val boss = system.actorOf(Props(new Actor {
        override val supervisorStrategy =
          OneForOneStrategy(maxNrOfRetries = 1, withinTimeRange = 5 seconds)(List(classOf[Throwable]))

        val crasher = context.watch(context.actorOf(Props(new CountDownActor(countDownMessages, SupervisorStrategy.defaultStrategy))))

        def receive = {
          case "killCrasher" ⇒ crasher ! Kill
          case Terminated(_) ⇒ countDownMax.countDown()
        }
      }))

      filterException[ActorKilledException] {
        boss ! "killCrasher"
        boss ! "killCrasher"

        assert(countDownMessages.await(2, TimeUnit.SECONDS))
        assert(countDownMax.await(2, TimeUnit.SECONDS))
      }
    }

    "resume children after Resume" in {
      val boss = system.actorOf(Props[Resumer], "resumer")
      boss ! "spawn"
      val middle = expectMsgType[ActorRef]
      middle ! "spawn"
      val worker = expectMsgType[ActorRef]
      worker ! "ping"
      expectMsg("pong")
      EventFilter[Exception]("expected", occurrences = 1) intercept {
        middle ! "fail"
      }
      middle ! "ping"
      expectMsg("pong")
      worker ! "ping"
      expectMsg("pong")
    }

    "suspend children while failing" in {
      val latch = TestLatch()
      val slowResumer = system.actorOf(Props(new Actor {
        override def supervisorStrategy = OneForOneStrategy() { case _ ⇒ Await.ready(latch, 4.seconds.dilated); SupervisorStrategy.Resume }
        def receive = {
          case "spawn" ⇒ sender ! context.actorOf(Props[Resumer])
        }
      }), "slowResumer")
      slowResumer ! "spawn"
      val boss = expectMsgType[ActorRef]
      boss ! "spawn"
      val middle = expectMsgType[ActorRef]
      middle ! "spawn"
      val worker = expectMsgType[ActorRef]
      worker ! "ping"
      expectMsg("pong")
      EventFilter[Exception]("expected", occurrences = 1) intercept {
        boss ! "fail"
      }
      awaitCond(worker.asInstanceOf[LocalActorRef].underlying.mailbox.isSuspended)
      worker ! "ping"
      expectNoMsg(2 seconds)
      latch.countDown()
      expectMsg("pong")
    }

    "survive being stressed" in {
      system.eventStream.publish(Mute(EventFilter[Failure]()))
      system.eventStream.publish(Mute(EventFilter.warning(start = "received dead letter")))

      val fsm = system.actorOf(Props(new StressTest(testActor, 6, 3)), "stressTest")

      fsm ! FSM.SubscribeTransitionCallBack(system.actorOf(Props(new Actor {
        def receive = {
          case s: FSM.CurrentState[_] ⇒ log.info("{}", s)
          case t: FSM.Transition[_]   ⇒ log.info("{}", t)
        }
      })))

      fsm ! Init

      expectMsg(70 seconds, "stressTestSuccessful")
      expectMsg("stressTestStopped")
    }
  }
}

