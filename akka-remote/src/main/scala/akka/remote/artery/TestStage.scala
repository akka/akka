/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.util.concurrent.atomic.AtomicReference

import scala.annotation.tailrec
import scala.concurrent.duration._
import akka.actor.Address
import akka.remote.transport.ThrottlerTransportAdapter.Direction
import akka.stream.Attributes
import akka.stream.FlowShape
import akka.stream.Inlet
import akka.stream.Outlet
import akka.stream.stage._
import akka.util.OptionVal
import akka.event.Logging

/**
 * INTERNAL API: Thread safe mutable state that is shared among
 * the test stages.
 */
private[remote] class SharedTestState {
  private val state = new AtomicReference[TestState](TestState(Map.empty))

  def isBlackhole(from: Address, to: Address): Boolean =
    state.get.blackholes.get(from) match {
      case Some(destinations) ⇒ destinations(to)
      case None               ⇒ false
    }

  def blackhole(a: Address, b: Address, direction: Direction): Unit =
    direction match {
      case Direction.Send    ⇒ addBlackhole(a, b)
      case Direction.Receive ⇒ addBlackhole(b, a)
      case Direction.Both ⇒
        addBlackhole(a, b)
        addBlackhole(b, a)
    }

  @tailrec private def addBlackhole(from: Address, to: Address): Unit = {
    val current = state.get
    val newState = current.blackholes.get(from) match {
      case Some(destinations) ⇒ current.copy(blackholes = current.blackholes.updated(from, destinations + to))
      case None               ⇒ current.copy(blackholes = current.blackholes.updated(from, Set(to)))
    }
    if (!state.compareAndSet(current, newState))
      addBlackhole(from, to)
  }

  def passThrough(a: Address, b: Address, direction: Direction): Unit =
    direction match {
      case Direction.Send    ⇒ removeBlackhole(a, b)
      case Direction.Receive ⇒ removeBlackhole(b, a)
      case Direction.Both ⇒
        removeBlackhole(a, b)
        removeBlackhole(b, a)
    }

  @tailrec private def removeBlackhole(from: Address, to: Address): Unit = {
    val current = state.get
    val newState = current.blackholes.get(from) match {
      case Some(destinations) ⇒ current.copy(blackholes = current.blackholes.updated(from, destinations - to))
      case None               ⇒ current
    }
    if (!state.compareAndSet(current, newState))
      removeBlackhole(from, to)
  }

}

/**
 * INTERNAL API
 */
private[remote] final case class TestState(blackholes: Map[Address, Set[Address]])

/**
 * INTERNAL API
 */
private[remote] class OutboundTestStage(outboundContext: OutboundContext, state: SharedTestState, enabled: Boolean)
  extends GraphStage[FlowShape[OutboundEnvelope, OutboundEnvelope]] {
  val in: Inlet[OutboundEnvelope] = Inlet("OutboundTestStage.in")
  val out: Outlet[OutboundEnvelope] = Outlet("OutboundTestStage.out")
  override val shape: FlowShape[OutboundEnvelope, OutboundEnvelope] = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes) = {
    if (enabled) {
      new TimerGraphStageLogic(shape) with InHandler with OutHandler with StageLogging {

        // InHandler
        override def onPush(): Unit = {
          val env = grab(in)
          if (state.isBlackhole(outboundContext.localAddress.address, outboundContext.remoteAddress)) {
            log.debug(
              "dropping outbound message [{}] to [{}] because of blackhole",
              Logging.messageClassName(env.message), outboundContext.remoteAddress)
            pull(in) // drop message
          } else
            push(out, env)
        }

        // OutHandler
        override def onPull(): Unit = pull(in)

        setHandlers(in, out, this)
      }
    } else {
      new GraphStageLogic(shape) with InHandler with OutHandler {
        override def onPush(): Unit = push(out, grab(in))
        override def onPull(): Unit = pull(in)
        setHandlers(in, out, this)
      }
    }
  }

}

/**
 * INTERNAL API
 */
private[remote] class InboundTestStage(inboundContext: InboundContext, state: SharedTestState, enabled: Boolean)
  extends GraphStage[FlowShape[InboundEnvelope, InboundEnvelope]] {
  val in: Inlet[InboundEnvelope] = Inlet("InboundTestStage.in")
  val out: Outlet[InboundEnvelope] = Outlet("InboundTestStage.out")
  override val shape: FlowShape[InboundEnvelope, InboundEnvelope] = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes) = {
    if (enabled) {
      new TimerGraphStageLogic(shape) with InHandler with OutHandler with StageLogging {

        // InHandler
        override def onPush(): Unit = {
          val env = grab(in)
          env.association match {
            case OptionVal.None ⇒
              // unknown, handshake not completed
              push(out, env)
            case OptionVal.Some(association) ⇒
              if (state.isBlackhole(inboundContext.localAddress.address, association.remoteAddress)) {
                log.debug(
                  "dropping inbound message [{}] from [{}] with UID [{}] because of blackhole",
                  Logging.messageClassName(env.message), association.remoteAddress, env.originUid)
                pull(in) // drop message
              } else
                push(out, env)
          }
        }

        // OutHandler
        override def onPull(): Unit = pull(in)

        setHandlers(in, out, this)
      }
    } else {
      new GraphStageLogic(shape) with InHandler with OutHandler {
        override def onPush(): Unit = push(out, grab(in))
        override def onPull(): Unit = pull(in)
        setHandlers(in, out, this)
      }
    }
  }

}

