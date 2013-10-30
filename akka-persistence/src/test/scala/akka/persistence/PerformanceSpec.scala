package akka.persistence

import scala.concurrent.duration._
import scala.language.postfixOps

import com.typesafe.config.ConfigFactory

import akka.actor._
import akka.testkit._

object PerformanceSpec {
  // multiply cycles with 100 for more
  // accurate throughput measurements
  val config =
    """
      akka.persistence.performance.cycles.warmup = 300
      akka.persistence.performance.cycles.load = 1000
    """

  case object StartMeasure
  case object StopMeasure
  case class FailAt(sequenceNr: Long)

  abstract class PerformanceTestProcessor(name: String) extends NamedProcessor(name) {
    val NanoToSecond = 1000.0 * 1000 * 1000

    var startTime: Long = 0L
    var stopTime: Long = 0L

    var startSequenceNr = 0L;
    var stopSequenceNr = 0L;

    var failAt: Long = -1

    val controlBehavior: Receive = {
      case StartMeasure ⇒ {
        startSequenceNr = lastSequenceNr
        startTime = System.nanoTime
      }
      case StopMeasure ⇒ {
        stopSequenceNr = lastSequenceNr
        stopTime = System.nanoTime
        sender ! (NanoToSecond * (stopSequenceNr - startSequenceNr) / (stopTime - startTime))
      }
      case FailAt(sequenceNr) ⇒ failAt = sequenceNr
    }

    override def postRestart(reason: Throwable) {
      super.postRestart(reason)
      receive(StartMeasure)
    }
  }

  class CommandsourcedTestProcessor(name: String) extends PerformanceTestProcessor(name) {
    def receive = controlBehavior orElse {
      case p: Persistent ⇒ {
        if (lastSequenceNr % 1000 == 0) if (recoveryRunning) print("r") else print(".")
        if (lastSequenceNr == failAt) throw new TestException("boom")
      }
    }
  }

  class EventsourcedTestProcessor(name: String) extends PerformanceTestProcessor(name) with EventsourcedProcessor {
    val receiveReplay: Receive = {
      case _ ⇒ if (lastSequenceNr % 1000 == 0) print("r")
    }

    val receiveCommand: Receive = controlBehavior orElse {
      case cmd ⇒ persist(cmd) { _ ⇒
        if (lastSequenceNr % 1000 == 0) print(".")
        if (lastSequenceNr == failAt) throw new TestException("boom")
      }
    }
  }

  class StashingEventsourcedTestProcessor(name: String) extends PerformanceTestProcessor(name) with EventsourcedProcessor {
    val receiveReplay: Receive = {
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
      case "c" ⇒ {
        persist("c")(_ ⇒ context.unbecome())
        unstashAll()
      }
      case other ⇒ stash()
    }
  }
}

class PerformanceSpec extends AkkaSpec(PersistenceSpec.config("leveldb", "performance").withFallback(ConfigFactory.parseString(PerformanceSpec.config))) with PersistenceSpec with ImplicitSender {
  import PerformanceSpec._

  val warmupClycles = system.settings.config.getInt("akka.persistence.performance.cycles.warmup")
  val loadCycles = system.settings.config.getInt("akka.persistence.performance.cycles.load")

  def stressCommandsourcedProcessor(failAt: Option[Long]): Unit = {
    val processor = namedProcessor[CommandsourcedTestProcessor]
    failAt foreach { processor ! FailAt(_) }
    1 to warmupClycles foreach { i ⇒ processor ! Persistent(s"msg${i}") }
    processor ! StartMeasure
    1 to loadCycles foreach { i ⇒ processor ! Persistent(s"msg${i}") }
    processor ! StopMeasure
    expectMsgPF(100 seconds) {
      case throughput: Double ⇒ println(f"\nthroughput = $throughput%.2f persistent commands per second")
    }
  }

  def stressEventsourcedProcessor(failAt: Option[Long]): Unit = {
    val processor = namedProcessor[EventsourcedTestProcessor]
    failAt foreach { processor ! FailAt(_) }
    1 to warmupClycles foreach { i ⇒ processor ! s"msg${i}" }
    processor ! StartMeasure
    1 to loadCycles foreach { i ⇒ processor ! s"msg${i}" }
    processor ! StopMeasure
    expectMsgPF(100 seconds) {
      case throughput: Double ⇒ println(f"\nthroughput = $throughput%.2f persistent events per second")
    }
  }

  def stressStashingEventsourcedProcessor(): Unit = {
    val processor = namedProcessor[StashingEventsourcedTestProcessor]
    1 to warmupClycles foreach { i ⇒ processor ! "b" }
    processor ! StartMeasure
    val cmds = 1 to (loadCycles / 3) flatMap (_ ⇒ List("a", "b", "c"))
    processor ! StartMeasure
    cmds foreach (processor ! _)
    processor ! StopMeasure
    expectMsgPF(100 seconds) {
      case throughput: Double ⇒ println(f"\nthroughput = $throughput%.2f persistent events per second")
    }
  }

  "A command sourced processor" should {
    "have some reasonable throughput" in {
      stressCommandsourcedProcessor(None)
    }
    "have some reasonable throughput under failure conditions" in {
      stressCommandsourcedProcessor(Some(warmupClycles + loadCycles / 10))
    }
  }

  "An event sourced processor" should {
    "have some reasonable throughput" in {
      stressEventsourcedProcessor(None)
    }
    "have some reasonable throughput under failure conditions" in {
      stressEventsourcedProcessor(Some(warmupClycles + loadCycles / 10))
    }
    "have some reasonable throughput with stashing and unstashing every 3rd command" in {
      stressStashingEventsourcedProcessor()
    }
  }
}
