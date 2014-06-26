/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.persistence

import scala.concurrent.duration._
import scala.language.postfixOps

import com.typesafe.config.ConfigFactory

import akka.actor._
import akka.testkit._

object PerformanceSpec {
  // multiply cycles with 200 for more
  // accurate throughput measurements
  val config =
    """
      akka.persistence.performance.cycles.warmup = 300
      akka.persistence.performance.cycles.load = 1000
    """

  case object StartMeasure
  case object StopMeasure
  final case class FailAt(sequenceNr: Long)

  trait Measure extends { this: Actor ⇒
    val NanoToSecond = 1000.0 * 1000 * 1000

    var startTime: Long = 0L
    var stopTime: Long = 0L

    var startSequenceNr = 0L
    var stopSequenceNr = 0L

    def startMeasure(): Unit = {
      startSequenceNr = lastSequenceNr
      startTime = System.nanoTime
    }

    def stopMeasure(): Unit = {
      stopSequenceNr = lastSequenceNr
      stopTime = System.nanoTime
      sender() ! (NanoToSecond * (stopSequenceNr - startSequenceNr) / (stopTime - startTime))
    }

    def lastSequenceNr: Long
  }

  class PerformanceTestDestination extends Actor with Measure {
    var lastSequenceNr = 0L

    val confirm: PartialFunction[Any, Any] = {
      case cp @ ConfirmablePersistent(payload, sequenceNr, _) ⇒
        lastSequenceNr = sequenceNr
        cp.confirm()
        payload
    }

    def receive = confirm andThen {
      case StartMeasure ⇒ startMeasure()
      case StopMeasure  ⇒ stopMeasure()
      case m            ⇒ if (lastSequenceNr % 1000 == 0) print(".")
    }
  }

  abstract class PerformanceTestProcessor(name: String) extends NamedProcessor(name) with Measure {
    var failAt: Long = -1

    val controlBehavior: Receive = {
      case StartMeasure       ⇒ startMeasure()
      case StopMeasure        ⇒ stopMeasure()
      case FailAt(sequenceNr) ⇒ failAt = sequenceNr
    }

    override def postRestart(reason: Throwable) {
      super.postRestart(reason)
      receive(StartMeasure)
    }
  }

  class CommandsourcedTestProcessor(name: String) extends PerformanceTestProcessor(name) {
    def receive = controlBehavior orElse {
      case p: Persistent ⇒
        if (lastSequenceNr % 1000 == 0) if (recoveryRunning) print("r") else print(".")
        if (lastSequenceNr == failAt) throw new TestException("boom")
    }
  }

  class CommandsourcedTestPersistentActor(name: String) extends PerformanceTestProcessor(name) with PersistentActor {

    override val controlBehavior: Receive = {
      case StartMeasure       ⇒ startMeasure()
      case StopMeasure        ⇒ defer(StopMeasure)(_ ⇒ stopMeasure())
      case FailAt(sequenceNr) ⇒ failAt = sequenceNr
    }

    val receiveRecover: Receive = {
      case _ ⇒ if (lastSequenceNr % 1000 == 0) print("r")
    }

    val receiveCommand: Receive = controlBehavior orElse {
      case cmd ⇒ persistAsync(cmd) { _ ⇒
        if (lastSequenceNr % 1000 == 0) print(".")
        if (lastSequenceNr == failAt) throw new TestException("boom")
      }
    }
  }

  class EventsourcedTestProcessor(name: String) extends PerformanceTestProcessor(name) with PersistentActor {
    val receiveRecover: Receive = {
      case _ ⇒ if (lastSequenceNr % 1000 == 0) print("r")
    }

    val receiveCommand: Receive = controlBehavior orElse {
      case cmd ⇒ persist(cmd) { _ ⇒
        if (lastSequenceNr % 1000 == 0) print(".")
        if (lastSequenceNr == failAt) throw new TestException("boom")
      }
    }
  }

  class StashingEventsourcedTestProcessor(name: String) extends PerformanceTestProcessor(name) with PersistentActor {
    val receiveRecover: Receive = {
      case _ ⇒ if (lastSequenceNr % 1000 == 0) print("r")
    }

    val printProgress: PartialFunction[Any, Any] = {
      case m ⇒ if (lastSequenceNr % 1000 == 0) print("."); m
    }

    val receiveCommand: Receive = printProgress andThen (controlBehavior orElse {
      case "a" ⇒ persist("a")(_ ⇒ context.become(processC))
      case "b" ⇒ persist("b")(_ ⇒ ())
    })

    val processC: Receive = printProgress andThen {
      case "c" ⇒
        persist("c")(_ ⇒ context.unbecome())
        unstashAll()
      case other ⇒ stash()
    }
  }
}

class PerformanceSpec extends AkkaSpec(PersistenceSpec.config("leveldb", "PerformanceSpec", serialization = "off").withFallback(ConfigFactory.parseString(PerformanceSpec.config))) with PersistenceSpec with ImplicitSender {
  import PerformanceSpec._

  val warmupCycles = system.settings.config.getInt("akka.persistence.performance.cycles.warmup")
  val loadCycles = system.settings.config.getInt("akka.persistence.performance.cycles.load")

  def stressCommandsourcedProcessor(failAt: Option[Long]): Unit = {
    val processor = namedProcessor[CommandsourcedTestProcessor]
    failAt foreach { processor ! FailAt(_) }
    1 to warmupCycles foreach { i ⇒ processor ! Persistent(s"msg${i}") }
    processor ! StartMeasure
    1 to loadCycles foreach { i ⇒ processor ! Persistent(s"msg${i}") }
    processor ! StopMeasure
    expectMsgPF(100 seconds) {
      case throughput: Double ⇒ println(f"\nthroughput = $throughput%.2f persistent processor commands per second")
    }
  }

  def stressCommandsourcedPersistentActor(failAt: Option[Long]): Unit = {
    val processor = namedProcessor[CommandsourcedTestPersistentActor]
    failAt foreach { processor ! FailAt(_) }
    1 to warmupCycles foreach { i ⇒ processor ! s"msg${i}" }
    processor ! StartMeasure
    1 to loadCycles foreach { i ⇒ processor ! s"msg${i}" }
    processor ! StopMeasure
    expectMsgPF(100 seconds) {
      case throughput: Double ⇒ println(f"\nthroughput = $throughput%.2f persistent actor commands per second")
    }
  }

  def stressPersistentActor(failAt: Option[Long]): Unit = {
    val processor = namedProcessor[EventsourcedTestProcessor]
    failAt foreach { processor ! FailAt(_) }
    1 to warmupCycles foreach { i ⇒ processor ! s"msg${i}" }
    processor ! StartMeasure
    1 to loadCycles foreach { i ⇒ processor ! s"msg${i}" }
    processor ! StopMeasure
    expectMsgPF(100 seconds) {
      case throughput: Double ⇒ println(f"\nthroughput = $throughput%.2f persistent events per second")
    }
  }

  def stressStashingPersistentActor(): Unit = {
    val processor = namedProcessor[StashingEventsourcedTestProcessor]
    1 to warmupCycles foreach { i ⇒ processor ! "b" }
    processor ! StartMeasure
    val cmds = 1 to (loadCycles / 3) flatMap (_ ⇒ List("a", "b", "c"))
    processor ! StartMeasure
    cmds foreach (processor ! _)
    processor ! StopMeasure
    expectMsgPF(100 seconds) {
      case throughput: Double ⇒ println(f"\nthroughput = $throughput%.2f persistent events per second")
    }
  }

  def stressPersistentChannel(): Unit = {
    val channel = system.actorOf(PersistentChannel.props())
    val destination = system.actorOf(Props[PerformanceTestDestination])
    1 to warmupCycles foreach { i ⇒ channel ! Deliver(PersistentRepr(s"msg${i}", persistenceId = "test"), destination.path) }
    channel ! Deliver(Persistent(StartMeasure), destination.path)
    1 to loadCycles foreach { i ⇒ channel ! Deliver(PersistentRepr(s"msg${i}", persistenceId = "test"), destination.path) }
    channel ! Deliver(Persistent(StopMeasure), destination.path)
    expectMsgPF(100 seconds) {
      case throughput: Double ⇒ println(f"\nthroughput = $throughput%.2f persistent messages per second")
    }
  }

  def subscribeToConfirmation(probe: TestProbe): Unit =
    system.eventStream.subscribe(probe.ref, classOf[DeliveredByPersistentChannel])

  def awaitConfirmation(probe: TestProbe): Unit =
    probe.expectMsgType[DeliveredByPersistentChannel]

  "A command sourced processor" should {
    "have some reasonable throughput" in {
      stressCommandsourcedProcessor(None)
    }
    "have some reasonable throughput under failure conditions" in {
      stressCommandsourcedProcessor(Some(warmupCycles + loadCycles / 10))
    }
  }

  "A command sourced persistent actor" should {
    "have some reasonable throughput" in {
      stressCommandsourcedPersistentActor(None)
    }
  }

  "An event sourced persistent actor" should {
    "have some reasonable throughput" in {
      stressPersistentActor(None)
    }
    "have some reasonable throughput under failure conditions" in {
      stressPersistentActor(Some(warmupCycles + loadCycles / 10))
    }
    "have some reasonable throughput with stashing and unstashing every 3rd command" in {
      stressStashingPersistentActor()
    }
  }

  "A persistent channel" should {
    "have some reasonable throughput" in {
      val probe = TestProbe()
      subscribeToConfirmation(probe)

      stressPersistentChannel()

      probe.fishForMessage(100.seconds) {
        case DeliveredByPersistentChannel(_, snr, _, _) ⇒ snr == warmupCycles + loadCycles + 2
      }
    }
  }
}
