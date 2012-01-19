/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.dispatch

import org.scalatest.Assertions._
import akka.testkit._
import akka.dispatch._
import akka.util.Timeout
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ ConcurrentHashMap, CountDownLatch, TimeUnit }
import akka.util.Switch
import java.rmi.RemoteException
import org.junit.{ After, Test }
import akka.actor._
import util.control.NoStackTrace
import akka.actor.ActorSystem
import akka.util.duration._
import akka.event.Logging.Error
import com.typesafe.config.Config
import akka.util.Duration

object ActorModelSpec {

  sealed trait ActorModelMessage

  case class TryReply(expect: Any) extends ActorModelMessage

  case class Reply(expect: Any) extends ActorModelMessage

  case class Forward(to: ActorRef, msg: Any) extends ActorModelMessage

  case class CountDown(latch: CountDownLatch) extends ActorModelMessage

  case class Increment(counter: AtomicLong) extends ActorModelMessage

  case class AwaitLatch(latch: CountDownLatch) extends ActorModelMessage

  case class Meet(acknowledge: CountDownLatch, waitFor: CountDownLatch) extends ActorModelMessage

  case class CountDownNStop(latch: CountDownLatch) extends ActorModelMessage

  case class Wait(time: Long) extends ActorModelMessage

  case class WaitAck(time: Long, latch: CountDownLatch) extends ActorModelMessage

  case object Interrupt extends ActorModelMessage

  case object Restart extends ActorModelMessage

  case class ThrowException(e: Throwable) extends ActorModelMessage

  val Ping = "Ping"
  val Pong = "Pong"

  class DispatcherActor extends Actor {
    private val busy = new Switch(false)

    def interceptor = context.dispatcher.asInstanceOf[MessageDispatcherInterceptor]

    def ack {
      if (!busy.switchOn()) {
        throw new Exception("isolation violated")
      } else {
        interceptor.getStats(self).msgsProcessed.incrementAndGet()
      }
    }

    override def postRestart(reason: Throwable) {
      interceptor.getStats(self).restarts.incrementAndGet()
    }

    def receive = {
      case AwaitLatch(latch)            ⇒ ack; latch.await(); busy.switchOff()
      case Meet(sign, wait)             ⇒ ack; sign.countDown(); wait.await(); busy.switchOff()
      case Wait(time)                   ⇒ ack; Thread.sleep(time); busy.switchOff()
      case WaitAck(time, l)             ⇒ ack; Thread.sleep(time); l.countDown(); busy.switchOff()
      case Reply(msg)                   ⇒ ack; sender ! msg; busy.switchOff()
      case TryReply(msg)                ⇒ ack; sender.tell(msg); busy.switchOff()
      case Forward(to, msg)             ⇒ ack; to.forward(msg); busy.switchOff()
      case CountDown(latch)             ⇒ ack; latch.countDown(); busy.switchOff()
      case Increment(count)             ⇒ ack; count.incrementAndGet(); busy.switchOff()
      case CountDownNStop(l)            ⇒ ack; l.countDown(); context.stop(self); busy.switchOff()
      case Restart                      ⇒ ack; busy.switchOff(); throw new Exception("Restart requested")
      case Interrupt                    ⇒ ack; sender ! Status.Failure(new ActorInterruptedException(new InterruptedException("Ping!"))); busy.switchOff(); throw new InterruptedException("Ping!")
      case ThrowException(e: Throwable) ⇒ ack; busy.switchOff(); throw e
    }
  }

  class InterceptorStats {
    val suspensions = new AtomicLong(0)
    val resumes = new AtomicLong(0)
    val registers = new AtomicLong(0)
    val unregisters = new AtomicLong(0)
    val msgsReceived = new AtomicLong(0)
    val msgsProcessed = new AtomicLong(0)
    val restarts = new AtomicLong(0)
    override def toString = "InterceptorStats(susp=" + suspensions +
      ",res=" + resumes + ",reg=" + registers + ",unreg=" + unregisters +
      ",recv=" + msgsReceived + ",proc=" + msgsProcessed + ",restart=" + restarts
  }

  trait MessageDispatcherInterceptor extends MessageDispatcher {
    val stats = new ConcurrentHashMap[ActorRef, InterceptorStats]
    val stops = new AtomicLong(0)

    def getStats(actorRef: ActorRef) = {
      stats.putIfAbsent(actorRef, new InterceptorStats) match {
        case null  ⇒ stats.get(actorRef)
        case other ⇒ other
      }
    }

    abstract override def suspend(actor: ActorCell) {
      getStats(actor.self).suspensions.incrementAndGet()
      super.suspend(actor)
    }

    abstract override def resume(actor: ActorCell) {
      super.resume(actor)
      getStats(actor.self).resumes.incrementAndGet()
    }

    protected[akka] abstract override def register(actor: ActorCell) {
      getStats(actor.self).registers.incrementAndGet()
      super.register(actor)
    }

    protected[akka] abstract override def unregister(actor: ActorCell) {
      getStats(actor.self).unregisters.incrementAndGet()
      super.unregister(actor)
    }

    protected[akka] abstract override def dispatch(receiver: ActorCell, invocation: Envelope) {
      val stats = getStats(receiver.self)
      stats.msgsReceived.incrementAndGet()
      super.dispatch(receiver, invocation)
    }

    protected[akka] abstract override def shutdown() {
      stops.incrementAndGet()
      super.shutdown()
    }
  }

  def assertDispatcher(dispatcher: MessageDispatcherInterceptor)(
    stops: Long = dispatcher.stops.get())(implicit system: ActorSystem) {
    val deadline = System.currentTimeMillis + dispatcher.shutdownTimeout.toMillis * 5
    try {
      await(deadline)(stops == dispatcher.stops.get)
    } catch {
      case e ⇒
        system.eventStream.publish(Error(e, dispatcher.toString, dispatcher.getClass, "actual: stops=" + dispatcher.stops.get +
          " required: stops=" + stops))
        throw e
    }
  }

  def assertCountDown(latch: CountDownLatch, wait: Long, hint: String) {
    if (!latch.await(wait, TimeUnit.MILLISECONDS))
      fail("Failed to count down within " + wait + " millis (count at " + latch.getCount + "). " + hint)
  }

  def assertNoCountDown(latch: CountDownLatch, wait: Long, hint: String) {
    if (latch.await(wait, TimeUnit.MILLISECONDS))
      fail("Expected count down to fail after " + wait + " millis. " + hint)
  }

  def statsFor(actorRef: ActorRef, dispatcher: MessageDispatcher = null) =
    dispatcher.asInstanceOf[MessageDispatcherInterceptor].getStats(actorRef)

  def assertRefDefaultZero(actorRef: ActorRef, dispatcher: MessageDispatcher = null)(
    suspensions: Long = 0,
    resumes: Long = 0,
    registers: Long = 0,
    unregisters: Long = 0,
    msgsReceived: Long = 0,
    msgsProcessed: Long = 0,
    restarts: Long = 0)(implicit system: ActorSystem) {
    assertRef(actorRef, dispatcher)(
      suspensions,
      resumes,
      registers,
      unregisters,
      msgsReceived,
      msgsProcessed,
      restarts)
  }

  def assertRef(actorRef: ActorRef, dispatcher: MessageDispatcher = null)(
    suspensions: Long = statsFor(actorRef).suspensions.get(),
    resumes: Long = statsFor(actorRef).resumes.get(),
    registers: Long = statsFor(actorRef).registers.get(),
    unregisters: Long = statsFor(actorRef).unregisters.get(),
    msgsReceived: Long = statsFor(actorRef).msgsReceived.get(),
    msgsProcessed: Long = statsFor(actorRef).msgsProcessed.get(),
    restarts: Long = statsFor(actorRef).restarts.get())(implicit system: ActorSystem) {
    val stats = statsFor(actorRef, Option(dispatcher).getOrElse(actorRef.asInstanceOf[LocalActorRef].underlying.dispatcher))
    val deadline = System.currentTimeMillis + 1000
    try {
      await(deadline)(stats.suspensions.get() == suspensions)
      await(deadline)(stats.resumes.get() == resumes)
      await(deadline)(stats.registers.get() == registers)
      await(deadline)(stats.unregisters.get() == unregisters)
      await(deadline)(stats.msgsReceived.get() == msgsReceived)
      await(deadline)(stats.msgsProcessed.get() == msgsProcessed)
      await(deadline)(stats.restarts.get() == restarts)
    } catch {
      case e ⇒
        system.eventStream.publish(Error(e,
          Option(dispatcher).toString,
          (Option(dispatcher) getOrElse this).getClass,
          "actual: " + stats + ", required: InterceptorStats(susp=" + suspensions +
            ",res=" + resumes + ",reg=" + registers + ",unreg=" + unregisters +
            ",recv=" + msgsReceived + ",proc=" + msgsProcessed + ",restart=" + restarts))
        throw e
    }
  }

  def await(until: Long)(condition: ⇒ Boolean): Unit = try {
    while (System.currentTimeMillis() <= until) {
      try {
        if (condition) return else Thread.sleep(25)
      } catch {
        case e: InterruptedException ⇒
      }
    }
    throw new AssertionError("await failed")
  }
}

abstract class ActorModelSpec(config: String) extends AkkaSpec(config) with DefaultTimeout {

  import ActorModelSpec._

  def newTestActor(dispatcher: String) = system.actorOf(Props[DispatcherActor].withDispatcher(dispatcher))

  protected def interceptedDispatcher(): MessageDispatcherInterceptor
  protected def dispatcherType: String

  "A " + dispatcherType must {

    "must dynamically handle its own life cycle" in {
      implicit val dispatcher = interceptedDispatcher()
      assertDispatcher(dispatcher)(stops = 0)
      val a = newTestActor(dispatcher.id)
      assertDispatcher(dispatcher)(stops = 0)
      system.stop(a)
      assertDispatcher(dispatcher)(stops = 1)
      assertRef(a, dispatcher)(
        suspensions = 0,
        resumes = 0,
        registers = 1,
        unregisters = 1,
        msgsReceived = 0,
        msgsProcessed = 0,
        restarts = 0)

      val futures = for (i ← 1 to 10) yield Future {
        i
      }
      assertDispatcher(dispatcher)(stops = 2)

      val a2 = newTestActor(dispatcher.id)
      val futures2 = for (i ← 1 to 10) yield Future { i }

      assertDispatcher(dispatcher)(stops = 2)

      system.stop(a2)
      assertDispatcher(dispatcher)(stops = 3)
    }

    "process messages one at a time" in {
      implicit val dispatcher = interceptedDispatcher()
      val start, oneAtATime = new CountDownLatch(1)
      val a = newTestActor(dispatcher.id)

      a ! CountDown(start)
      assertCountDown(start, 3.seconds.dilated.toMillis, "Should process first message within 3 seconds")
      assertRefDefaultZero(a)(registers = 1, msgsReceived = 1, msgsProcessed = 1)

      a ! Wait(1000)
      a ! CountDown(oneAtATime)
      // in case of serialization violation, restart would happen instead of count down
      assertCountDown(oneAtATime, (1.5 seconds).dilated.toMillis, "Processed message when allowed")
      assertRefDefaultZero(a)(registers = 1, msgsReceived = 3, msgsProcessed = 3)

      system.stop(a)
      assertRefDefaultZero(a)(registers = 1, unregisters = 1, msgsReceived = 3, msgsProcessed = 3)
    }

    "handle queueing from multiple threads" in {
      implicit val dispatcher = interceptedDispatcher()
      val counter = new CountDownLatch(200)
      val a = newTestActor(dispatcher.id)

      for (i ← 1 to 10) {
        spawn {
          for (i ← 1 to 20) {
            a ! WaitAck(1, counter)
          }
        }
      }
      assertCountDown(counter, 3.seconds.dilated.toMillis, "Should process 200 messages")
      assertRefDefaultZero(a)(registers = 1, msgsReceived = 200, msgsProcessed = 200)

      system.stop(a)
    }

    def spawn(f: ⇒ Unit) {
      val thread = new Thread {
        override def run {
          try {
            f
          } catch {
            case e ⇒ system.eventStream.publish(Error(e, "spawn", this.getClass, "error in spawned thread"))
          }
        }
      }
      thread.start()
    }

    "not process messages for a suspended actor" in {
      implicit val dispatcher = interceptedDispatcher()
      val a = newTestActor(dispatcher.id).asInstanceOf[LocalActorRef]
      val done = new CountDownLatch(1)
      a.suspend
      a ! CountDown(done)
      assertNoCountDown(done, 1000, "Should not process messages while suspended")
      assertRefDefaultZero(a)(registers = 1, msgsReceived = 1, suspensions = 1)

      a.resume
      assertCountDown(done, 3.seconds.dilated.toMillis, "Should resume processing of messages when resumed")
      assertRefDefaultZero(a)(registers = 1, msgsReceived = 1, msgsProcessed = 1,
        suspensions = 1, resumes = 1)

      system.stop(a)
      assertRefDefaultZero(a)(registers = 1, unregisters = 1, msgsReceived = 1, msgsProcessed = 1,
        suspensions = 1, resumes = 1)
    }

    "handle waves of actors" in {
      val dispatcher = interceptedDispatcher()
      val props = Props[DispatcherActor].withDispatcher(dispatcher.id)

      def flood(num: Int) {
        val cachedMessage = CountDownNStop(new CountDownLatch(num))
        val stopLatch = new CountDownLatch(num)
        val waitTime = (30 seconds).dilated.toMillis
        val boss = system.actorOf(Props(new Actor {
          def receive = {
            case "run"             ⇒ for (_ ← 1 to num) (context.watch(context.actorOf(props))) ! cachedMessage
            case Terminated(child) ⇒ stopLatch.countDown()
          }
        }).withDispatcher("boss"))
        boss ! "run"
        try {
          assertCountDown(cachedMessage.latch, waitTime, "Counting down from " + num)
        } catch {
          case e ⇒
            dispatcher match {
              case dispatcher: BalancingDispatcher ⇒
                val buddies = dispatcher.buddies
                val mq = dispatcher.messageQueue

                System.err.println("Buddies left: ")
                buddies.toArray foreach {
                  case cell: ActorCell ⇒
                    System.err.println(" - " + cell.self.path + " " + cell.isTerminated + " " + cell.mailbox.status + " " + cell.mailbox.numberOfMessages + " " + SystemMessage.size(cell.mailbox.systemDrain()))
                }

                System.err.println("Mailbox: " + mq.numberOfMessages + " " + mq.hasMessages + " ")
              case _ ⇒
            }

            throw e
        }
        assertCountDown(stopLatch, waitTime, "Expected all children to stop")
        system.stop(boss)
      }
      for (run ← 1 to 3) {
        flood(50000)
        assertDispatcher(dispatcher)(stops = run)
      }
    }

    "continue to process messages when a thread gets interrupted" in {
      filterEvents(EventFilter[InterruptedException](), EventFilter[akka.event.Logging.EventHandlerException]()) {
        implicit val dispatcher = interceptedDispatcher()
        implicit val timeout = Timeout(5 seconds)
        val a = newTestActor(dispatcher.id)
        val f1 = a ? Reply("foo")
        val f2 = a ? Reply("bar")
        val f3 = try { a ? Interrupt } catch { case ie: InterruptedException ⇒ Promise.failed(ActorInterruptedException(ie)) }
        val f4 = a ? Reply("foo2")
        val f5 = try { a ? Interrupt } catch { case ie: InterruptedException ⇒ Promise.failed(ActorInterruptedException(ie)) }
        val f6 = a ? Reply("bar2")

        assert(Await.result(f1, timeout.duration) === "foo")
        assert(Await.result(f2, timeout.duration) === "bar")
        assert(Await.result(f4, timeout.duration) === "foo2")
        assert(intercept[ActorInterruptedException](Await.result(f3, timeout.duration)).getMessage === "Ping!")
        assert(Await.result(f6, timeout.duration) === "bar2")
        assert(intercept[ActorInterruptedException](Await.result(f5, timeout.duration)).getMessage === "Ping!")
      }
    }

    "continue to process messages when exception is thrown" in {
      filterEvents(EventFilter[IndexOutOfBoundsException](), EventFilter[RemoteException]()) {
        implicit val dispatcher = interceptedDispatcher()
        val a = newTestActor(dispatcher.id)
        val f1 = a ? Reply("foo")
        val f2 = a ? Reply("bar")
        val f3 = a ? ThrowException(new IndexOutOfBoundsException("IndexOutOfBoundsException"))
        val f4 = a ? Reply("foo2")
        val f5 = a ? ThrowException(new RemoteException("RemoteException"))
        val f6 = a ? Reply("bar2")

        assert(Await.result(f1, timeout.duration) === "foo")
        assert(Await.result(f2, timeout.duration) === "bar")
        assert(Await.result(f4, timeout.duration) === "foo2")
        assert(Await.result(f6, timeout.duration) === "bar2")
        assert(f3.value.isEmpty)
        assert(f5.value.isEmpty)
      }
    }
  }
}

object DispatcherModelSpec {
  import ActorModelSpec._

  val config = {
    """
      boss {
        type = PinnedDispatcher
      }
    """ +
      // use unique dispatcher id for each test, since MessageDispatcherInterceptor holds state
      (for (n ← 1 to 30) yield """
        test-dispatcher-%s {
          type = "akka.actor.dispatch.DispatcherModelSpec$MessageDispatcherInterceptorConfigurator"
        }""".format(n)).mkString
  }

  class MessageDispatcherInterceptorConfigurator(config: Config, prerequisites: DispatcherPrerequisites)
    extends MessageDispatcherConfigurator(config, prerequisites) {

    private val instance: MessageDispatcher = {
      configureThreadPool(config,
        threadPoolConfig ⇒ new Dispatcher(prerequisites,
          config.getString("name"),
          config.getString("id"),
          config.getInt("throughput"),
          Duration(config.getNanoseconds("throughput-deadline-time"), TimeUnit.NANOSECONDS),
          mailboxType,
          threadPoolConfig,
          Duration(config.getMilliseconds("shutdown-timeout"), TimeUnit.MILLISECONDS)) with MessageDispatcherInterceptor).build
    }

    override def dispatcher(): MessageDispatcher = instance
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class DispatcherModelSpec extends ActorModelSpec(DispatcherModelSpec.config) {
  import ActorModelSpec._

  val dispatcherCount = new AtomicInteger()

  override def interceptedDispatcher(): MessageDispatcherInterceptor = {
    // use new id for each test, since the MessageDispatcherInterceptor holds state
    system.dispatchers.lookup("test-dispatcher-" + dispatcherCount.incrementAndGet()).asInstanceOf[MessageDispatcherInterceptor]
  }

  override def dispatcherType = "Dispatcher"

  "A " + dispatcherType must {
    "process messages in parallel" in {
      implicit val dispatcher = interceptedDispatcher()
      val aStart, aStop, bParallel = new CountDownLatch(1)
      val a, b = newTestActor(dispatcher.id)

      a ! Meet(aStart, aStop)
      assertCountDown(aStart, 3.seconds.dilated.toMillis, "Should process first message within 3 seconds")

      b ! CountDown(bParallel)
      assertCountDown(bParallel, 3.seconds.dilated.toMillis, "Should process other actors in parallel")

      aStop.countDown()

      system.stop(a)
      system.stop(b)

      while (!a.isTerminated && !b.isTerminated) {} //Busy wait for termination

      assertRefDefaultZero(a)(registers = 1, unregisters = 1, msgsReceived = 1, msgsProcessed = 1)
      assertRefDefaultZero(b)(registers = 1, unregisters = 1, msgsReceived = 1, msgsProcessed = 1)
    }
  }
}

object BalancingDispatcherModelSpec {
  import ActorModelSpec._

  // TODO check why throughput=1 here? (came from old test)
  val config = {
    """
      boss {
        type = PinnedDispatcher
      }
    """ +
      // use unique dispatcher id for each test, since MessageDispatcherInterceptor holds state
      (for (n ← 1 to 30) yield """
        test-balancing-dispatcher-%s {
          type = "akka.actor.dispatch.BalancingDispatcherModelSpec$BalancingMessageDispatcherInterceptorConfigurator"
          throughput=1
        }""".format(n)).mkString
  }

  class BalancingMessageDispatcherInterceptorConfigurator(config: Config, prerequisites: DispatcherPrerequisites)
    extends MessageDispatcherConfigurator(config, prerequisites) {

    private val instance: MessageDispatcher = {
      configureThreadPool(config,
        threadPoolConfig ⇒ new BalancingDispatcher(prerequisites,
          config.getString("name"),
          config.getString("id"),
          config.getInt("throughput"),
          Duration(config.getNanoseconds("throughput-deadline-time"), TimeUnit.NANOSECONDS),
          mailboxType,
          threadPoolConfig,
          Duration(config.getMilliseconds("shutdown-timeout"), TimeUnit.MILLISECONDS)) with MessageDispatcherInterceptor).build
    }

    override def dispatcher(): MessageDispatcher = instance
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class BalancingDispatcherModelSpec extends ActorModelSpec(BalancingDispatcherModelSpec.config) {
  import ActorModelSpec._

  val dispatcherCount = new AtomicInteger()

  override def interceptedDispatcher(): MessageDispatcherInterceptor = {
    // use new id for each test, since the MessageDispatcherInterceptor holds state
    system.dispatchers.lookup("test-balancing-dispatcher-" + dispatcherCount.incrementAndGet()).asInstanceOf[MessageDispatcherInterceptor]
  }

  override def dispatcherType = "Balancing Dispatcher"

  "A " + dispatcherType must {
    "process messages in parallel" in {
      implicit val dispatcher = interceptedDispatcher()
      val aStart, aStop, bParallel = new CountDownLatch(1)
      val a, b = newTestActor(dispatcher.id)

      a ! Meet(aStart, aStop)
      assertCountDown(aStart, 3.seconds.dilated.toMillis, "Should process first message within 3 seconds")

      b ! CountDown(bParallel)
      assertCountDown(bParallel, 3.seconds.dilated.toMillis, "Should process other actors in parallel")

      aStop.countDown()

      system.stop(a)
      system.stop(b)

      while (!a.isTerminated && !b.isTerminated) {} //Busy wait for termination

      assertRefDefaultZero(a)(registers = 1, unregisters = 1, msgsReceived = 1, msgsProcessed = 1)
      assertRefDefaultZero(b)(registers = 1, unregisters = 1, msgsReceived = 1, msgsProcessed = 1)
    }
  }
}
