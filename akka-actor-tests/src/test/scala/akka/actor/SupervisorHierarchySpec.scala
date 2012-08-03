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
import SupervisorStrategy.{ Resume, Restart, Stop, Directive }
import akka.actor.SupervisorStrategy.seqThrowable2Decider
import akka.dispatch.{ MessageDispatcher, DispatcherPrerequisites, DispatcherConfigurator, Dispatcher }
import akka.pattern.ask
import akka.testkit.{ ImplicitSender, EventFilter, DefaultTimeout, AkkaSpec }
import akka.testkit.{ filterException, duration2TestDuration, TestLatch }
import akka.testkit.TestEvent.Mute
import java.util.concurrent.ConcurrentHashMap

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

  case class Children(refs: Vector[ActorRef])
  case class Event(msg: Any) { val time: Long = System.nanoTime }
  case class ErrorLog(msg: String, log: Vector[Event])
  case class Failure(directive: Directive, depth: Int, var failPre: Int, var failPost: Int) extends RuntimeException with NoStackTrace {
    override def toString = productPrefix + productIterator.mkString("(", ",", ")")
  }
  case class Dump(level: Int)

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
          a.log :+= Event("suspended " + cell.mailbox.status / 4)
          super.suspend(cell)
        }

        override def resume(cell: ActorCell): Unit = {
          val a = cell.actor.asInstanceOf[Hierarchy]
          super.resume(cell)
          a.log :+= Event("resumed " + cell.mailbox.status / 4)
        }

      }

    override def dispatcher(): MessageDispatcher = instance
  }

  /*
   * This stores structural data of the hierarchy which would otherwise be lost
   * upon Restart or would have to be managed by the highest supervisor (which
   * is undesirable).
   */
  case class HierarchyState(log: Vector[Event], kids: Map[String, Int])
  val stateCache = new ConcurrentHashMap[ActorRef, HierarchyState]()

  class Hierarchy(size: Int, breadth: Int, listener: ActorRef) extends Actor {

    var failed = false
    var suspended = false

    def abort(msg: String) {
      listener ! ErrorLog(msg, log)
      log = Vector(Event("log sent"))
      context stop self
    }

    def setFlags(directive: Directive): Unit = directive match {
      case Restart ⇒ failed = true
      case Resume  ⇒ suspended = true
      case _       ⇒
    }

    def suspendCount = context.asInstanceOf[ActorCell].mailbox.status / 4

    override def preStart {
      log :+= Event("started")
      val s = size - 1 // subtract myself
      val kidInfo: Map[String, Int] =
        if (s > 0) {
          val kids = Random.nextInt(Math.min(breadth, s)) + 1
          val sizes = s / kids
          var rest = s % kids
          val propsTemplate = Props.empty.withDispatcher("hierarchy")
          (1 to kids).map { (id) ⇒
            val kidSize = if (rest > 0) { rest -= 1; sizes + 1 } else sizes
            val props = propsTemplate.withCreator(new Hierarchy(kidSize, breadth, listener))
            val name = id.toString
            context.watch(context.actorOf(props, name))
            (name, kidSize)
          }(collection.breakOut)
        } else {
          context.parent ! Children(Vector(self))
          Map()
        }
      stateCache.put(self, HierarchyState(log, kidInfo))
    }

    var preRestartCalled = false
    override def preRestart(cause: Throwable, msg: Option[Any]): Unit = {
      // do not scrap children
      if (preRestartCalled) abort("preRestart called twice")
      else {
        log :+= Event("preRestart")
        stateCache.put(self, stateCache.get(self).copy(log = log))
        preRestartCalled = true
        cause match {
          case f @ Failure(_, _, x, _) if x > 0 ⇒ f.failPre = x - 1; throw f
          case _                                ⇒
        }
      }
    }

    override val supervisorStrategy = OneForOneStrategy() {
      case Failure(directive, 0, _, _) ⇒
        log :+= Event("applying " + directive + " to " + sender)
        directive
      case OriginalRestartException(f: Failure) ⇒
        log :+= Event("re-applying " + f.directive + " to " + sender)
        f.directive
      case f @ Failure(directive, x, _, _) ⇒
        import SupervisorStrategy._
        setFlags(directive)
        log :+= Event("escalating " + f)
        throw f.copy(depth = x - 1)
    }

    override def postRestart(cause: Throwable) {
      log = stateCache.get(self).log
      log :+= Event("restarted " + suspendCount)
      cause match {
        case f @ Failure(_, _, _, x) if x > 0 ⇒ f.failPost = x - 1; throw f
        case _                                ⇒
      }
    }

    override def postStop {
      if (failed || suspended) {
        listener ! ErrorLog("not resumed (" + failed + ", " + suspended + ")", log)
      }
    }

    var log = Vector.empty[Event]
    def check(msg: Any): Boolean = {
      suspended = false
      log :+= Event(msg)
      if (failed) {
        abort("processing message while failed")
        failed = false
        false
      } else if (context.asInstanceOf[ActorCell].mailbox.isSuspended) {
        abort("processing message while suspended")
        false
      } else true
    }

    var gotKidsFrom = Set.empty[ActorRef]
    var kids = Vector.empty[ActorRef]

    def receive = new Receive {
      val handler: Receive = {
        case f: Failure    ⇒ setFlags(f.directive); throw f
        case "ping"        ⇒ Thread.sleep((Random.nextFloat * 1.03).toLong); sender ! "pong"
        case Dump(0)       ⇒ context stop self
        case Dump(level)   ⇒ context.children foreach (_ ! Dump(level - 1))
        case Terminated(_) ⇒ abort("terminating")
        case Children(refs) ⇒
          kids ++= refs
          gotKidsFrom += sender
          if (context.children forall (gotKidsFrom contains)) context.parent ! Children(kids :+ self)
      }
      override def isDefinedAt(msg: Any) = handler.isDefinedAt(msg)
      override def apply(msg: Any) = { if (check(msg)) handler(msg) }
    }
  }

  case object Work

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
   * TODO RK: also test recreate including terminating children
   * 
   * TODO RK: test exceptions in constructor
   */

  class StressTest(testActor: ActorRef, depth: Int, breadth: Int) extends Actor with LoggingFSM[State, Int] {
    import context.system

    // don’t escalate from this one!
    override val supervisorStrategy = OneForOneStrategy() {
      case f: Failure                           ⇒ f.directive
      case OriginalRestartException(f: Failure) ⇒ f.directive
    }

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

    // number of Work packages to execute for the test
    startWith(Idle, 500000)

    when(Idle) {
      case Event(Init, _) ⇒
        hierarchy = context.watch(context.actorOf(Props(new Hierarchy(size = 500, breadth = breadth, self)).withDispatcher("hierarchy"), "head"))
        setTimer("phase", StateTimeout, 5 seconds, false)
        goto(Init)
    }

    when(Init) {
      case Event(Children(refs), _) ⇒
        children = refs
        idleChildren = refs
        if (children.toSet.size != children.size) {
          testActor ! "children not unique"
          stop()
        } else {
          goto(Stress)
        }
      case Event(StateTimeout, _) ⇒
        testActor ! "did not get children list"
        stop()
    }

    onTransition {
      case Init -> Stress ⇒
        self ! Work
        // set timeout for completion of the whole test (i.e. including Finishing and Stopping)
        setTimer("phase", StateTimeout, 50.seconds.dilated, false)
    }

    val workSchedule = 250.millis

    private def random012: Int = Random.nextFloat match {
      case x if x > 0.1  ⇒ 0
      case x if x > 0.03 ⇒ 1
      case _             ⇒ 2
    }

    var ignoreNotResumedLogs = true

    when(Stress) {
      case Event(Work, _) if idleChildren.isEmpty ⇒
        ignoreNotResumedLogs = false
        context stop hierarchy
        goto(Failed)
      case Event(Work, x) if x > 0 ⇒
        nextJob.next match {
          case Ping(ref)      ⇒ ref ! "ping"
          case Fail(ref, dir) ⇒ ref ! Failure(dir, random012, random012, random012)
        }
        if (idleChildren.nonEmpty) self ! Work
        else context.system.scheduler.scheduleOnce(workSchedule, self, Work)
        stay using (x - 1)
      case Event(Work, _) ⇒ if (pingChildren.isEmpty) goto(LastPing) else goto(Finishing)
      case Event("pong", _) ⇒
        pingChildren -= sender
        idleChildren :+= sender
        stay
      case Event(StateTimeout, todo) ⇒
        log.info("dumping state due to StateTimeout")
        log.info("children: " + children.size + " pinged: " + pingChildren.size + " idle: " + idleChildren.size + " work: " + todo)
        println(system.asInstanceOf[ActorSystemImpl].printTree)
        ignoreNotResumedLogs = false
        hierarchy ! Dump(2)
        goto(Failed)
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
      case _ -> Stopping ⇒
        ignoreNotResumedLogs = false
        context stop hierarchy
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
        if (!e.msg.startsWith("not resumed") || !ignoreNotResumedLogs)
          errors :+= sender -> e
        stay
      case Event(Terminated(r), _) if r == hierarchy ⇒
        printErrors()
        testActor ! "stressTestFailed"
        stop
      case Event(StateTimeout, _) ⇒
        getErrors()
        printErrors()
        testActor ! "timeout in Failed"
        stop
      case Event("pong", _) ⇒ stay // don’t care?
      case Event(Work, _)   ⇒ stay
    }

    def getErrors() = {
      def rec(target: ActorRef, depth: Int): Unit = {
        target match {
          case l: LocalActorRef ⇒
            errors :+= target -> ErrorLog("forced", l.underlying.actor.asInstanceOf[Hierarchy].log)
            if (depth > 0) {
              l.underlying.children foreach (rec(_, depth - 1))
            }
        }
      }
      rec(hierarchy, 2)
    }

    def printErrors(): Unit = {
      val merged = errors flatMap {
        case (ref, ErrorLog(msg, log)) ⇒
          println(ref + " " + msg)
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
      system.eventStream.publish(Mute(EventFilter[Failure](), EventFilter[PreRestartException](), EventFilter[PostRestartException]()))
      system.eventStream.publish(Mute(EventFilter.warning(start = "received dead letter")))

      val fsm = system.actorOf(Props(new StressTest(testActor, depth = 6, breadth = 6)), "stressTest")

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

