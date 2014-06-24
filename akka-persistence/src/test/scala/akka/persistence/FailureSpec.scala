/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import scala.concurrent.duration._
import scala.concurrent.forkjoin.ThreadLocalRandom
import scala.language.postfixOps

import com.typesafe.config.ConfigFactory

import akka.actor._
import akka.testkit._

object FailureSpec {
  val config = ConfigFactory.parseString(
    s"""
      akka.persistence.processor.chaos.live-processing-failure-rate = 0.3
      akka.persistence.processor.chaos.replay-processing-failure-rate = 0.1
      akka.persistence.destination.chaos.confirm-failure-rate = 0.3
      akka.persistence.journal.plugin = "akka.persistence.journal.chaos"
      akka.persistence.journal.chaos.write-failure-rate = 0.3
      akka.persistence.journal.chaos.confirm-failure-rate = 0.2
      akka.persistence.journal.chaos.delete-failure-rate = 0.3
      akka.persistence.journal.chaos.replay-failure-rate = 0.25
      akka.persistence.journal.chaos.read-highest-failure-rate = 0.1
      akka.persistence.journal.chaos.class = akka.persistence.journal.chaos.ChaosJournal
      akka.persistence.snapshot-store.local.dir = "target/snapshots-failure-spec/"
    """)

  val numMessages = 10

  case object Start
  final case class Done(ints: Vector[Int])

  final case class ProcessingFailure(i: Int)
  final case class JournalingFailure(i: Int)

  trait ChaosSupport { this: Actor ⇒
    def random = ThreadLocalRandom.current

    var state = Vector.empty[Int]

    def contains(i: Int): Boolean =
      state.contains(i)

    def add(i: Int): Unit = {
      state :+= i
      if (state.length == numMessages) sender() ! Done(state)
    }

    def shouldFail(rate: Double) =
      random.nextDouble() < rate
  }

  class ChaosProcessor(destination: ActorRef) extends Processor with ChaosSupport with ActorLogging {
    val config = context.system.settings.config.getConfig("akka.persistence.processor.chaos")
    val liveProcessingFailureRate = config.getDouble("live-processing-failure-rate")
    val replayProcessingFailureRate = config.getDouble("replay-processing-failure-rate")

    val channel = context.actorOf(Channel.props("channel", ChannelSettings(redeliverMax = 10, redeliverInterval = 500 milliseconds)), "channel")

    override def persistenceId = "chaos"

    def receive = {
      case p @ Persistent(i: Int, _) ⇒
        val failureRate = if (recoveryRunning) replayProcessingFailureRate else liveProcessingFailureRate
        if (contains(i)) {
          log.debug(debugMessage(s"ignored duplicate ${i}"))
        } else if (shouldFail(failureRate)) {
          throw new TestException(debugMessage(s"rejected payload ${i}"))
        } else {
          add(i)
          channel forward Deliver(p, destination.path)
          log.debug(debugMessage(s"processed payload ${i}"))
        }
      case PersistenceFailure(i: Int, _, _) ⇒
        // inform sender about journaling failure so that it can resend
        sender() ! JournalingFailure(i)
      case RecoveryFailure(_) ⇒
        // journal failed during recovery, throw exception to re-recover processor
        throw new TestException(debugMessage("recovery failed"))
    }

    override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
      message match {
        case Some(p @ Persistent(i: Int, _)) if !recoveryRunning ⇒
          deleteMessage(p.sequenceNr)
          log.debug(debugMessage(s"requested deletion of payload ${i}"))
          // inform sender about processing failure so that it can resend
          sender() ! ProcessingFailure(i)
        case _ ⇒
      }
      super.preRestart(reason, message)
    }

    private def debugMessage(msg: String): String =
      s"[processor] ${msg} (mode = ${if (recoveryRunning) "replay" else "live"} snr = ${lastSequenceNr} state = ${state.sorted})"
  }

  class ChaosDestination extends Actor with ChaosSupport with ActorLogging {
    val config = context.system.settings.config.getConfig("akka.persistence.destination.chaos")
    val confirmFailureRate = config.getDouble("confirm-failure-rate")

    def receive = {
      case cp @ ConfirmablePersistent(i: Int, _, _) ⇒
        if (shouldFail(confirmFailureRate)) {
          log.error(debugMessage("confirm message failed", cp))
        } else if (contains(i)) {
          log.debug(debugMessage("ignored duplicate", cp))
        } else {
          add(i)
          cp.confirm()
          log.debug(debugMessage("received and confirmed message", cp))
        }
    }

    private def debugMessage(msg: String, cp: ConfirmablePersistent): String =
      s"[destination] ${msg} (message = ConfirmablePersistent(${cp.payload}, ${cp.sequenceNr}, ${cp.redeliveries}), state = ${state.sorted})"
  }

  class ChaosProcessorApp(probe: ActorRef) extends Actor with ActorLogging {
    val destination = context.actorOf(Props[ChaosDestination], "destination")
    val processor = context.actorOf(Props(classOf[ChaosProcessor], destination), "processor")

    def receive = {
      case Start      ⇒ 1 to numMessages foreach (processor ! Persistent(_))
      case Done(ints) ⇒ probe ! Done(ints)
      case ProcessingFailure(i) ⇒
        processor ! Persistent(i)
        log.debug(s"resent ${i} after processing failure")
      case JournalingFailure(i) ⇒
        processor ! Persistent(i)
        log.debug(s"resent ${i} after journaling failure")
    }
  }
}

class FailureSpec extends AkkaSpec(FailureSpec.config) with Cleanup with ImplicitSender {
  import FailureSpec._

  "The journaling protocol (= conversation between a processor and a journal)" must {
    "tolerate and recover from random failures" in {
      system.actorOf(Props(classOf[ChaosProcessorApp], testActor)) ! Start
      expectDone() // by processor
      expectDone() // by destination

      system.actorOf(Props(classOf[ChaosProcessorApp], testActor)) // recovery of new instance should have same outcome
      expectDone() // by processor
      // destination doesn't receive messages again because all have been confirmed already
    }
  }

  def expectDone() =
    expectMsgPF(numMessages seconds) { case Done(ints) ⇒ ints.sorted should be(1 to numMessages toVector) }
}
