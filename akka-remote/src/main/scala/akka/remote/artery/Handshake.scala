/*
 * Copyright (C) 2016-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery

import scala.concurrent.duration._
import scala.concurrent.Future
import scala.util.control.NoStackTrace

import akka.actor.ActorSystem
import akka.remote.UniqueAddress
import akka.stream.Attributes
import akka.stream.FlowShape
import akka.stream.Inlet
import akka.stream.Outlet
import akka.stream.stage._
import akka.util.{ OptionVal, unused }
import akka.Done
import akka.actor.Address

/**
 * INTERNAL API
 */
private[remote] object OutboundHandshake {

  /**
   * Stream is failed with this exception if the handshake is not completed
   * within the handshake timeout.
   */
  class HandshakeTimeoutException(msg: String) extends RuntimeException(msg) with NoStackTrace

  final case class HandshakeReq(from: UniqueAddress, to: Address) extends ControlMessage
  final case class HandshakeRsp(from: UniqueAddress) extends Reply

  private sealed trait HandshakeState
  private case object Start extends HandshakeState
  private case object ReqInProgress extends HandshakeState
  private case object Completed extends HandshakeState

  private case object HandshakeTimeout
  private case object HandshakeRetryTick
  private case object InjectHandshakeTick
  private case object LivenessProbeTick

}

/**
 * INTERNAL API
 */
private[remote] class OutboundHandshake(
  @unused system:          ActorSystem,
  outboundContext:         OutboundContext,
  outboundEnvelopePool:    ObjectPool[ReusableOutboundEnvelope],
  timeout:                 FiniteDuration,
  retryInterval:           FiniteDuration,
  injectHandshakeInterval: FiniteDuration,
  livenessProbeInterval:   Duration)
  extends GraphStage[FlowShape[OutboundEnvelope, OutboundEnvelope]] {

  val in: Inlet[OutboundEnvelope] = Inlet("OutboundHandshake.in")
  val out: Outlet[OutboundEnvelope] = Outlet("OutboundHandshake.out")
  override val shape: FlowShape[OutboundEnvelope, OutboundEnvelope] = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new TimerGraphStageLogic(shape) with InHandler with OutHandler with StageLogging {
      import OutboundHandshake._

      private var handshakeState: HandshakeState = Start
      private var pendingMessage: OptionVal[OutboundEnvelope] = OptionVal.None
      private var injectHandshakeTickScheduled = false

      override protected def logSource: Class[_] = classOf[OutboundHandshake]

      override def preStart(): Unit = {
        scheduleOnce(HandshakeTimeout, timeout)
        livenessProbeInterval match {
          case d: FiniteDuration ⇒ schedulePeriodically(LivenessProbeTick, d)
          case _                 ⇒ // only used in control stream
        }
      }

      // InHandler
      override def onPush(): Unit = {
        if (handshakeState != Completed)
          throw new IllegalStateException(s"onPush before handshake completed, was [$handshakeState].")

        // inject a HandshakeReq once in a while to trigger a new handshake when destination
        // system has been restarted
        if (injectHandshakeTickScheduled) {
          // out is always available here, except for if a liveness HandshakeReq was just pushed
          if (isAvailable(out))
            push(out, grab(in))
          else {
            if (pendingMessage.isDefined)
              throw new IllegalStateException(s"pendingMessage expected to be empty")
            pendingMessage = OptionVal.Some(grab(in))
          }
        } else {
          pushHandshakeReq()
          pendingMessage = OptionVal.Some(grab(in))
        }
      }

      // OutHandler
      override def onPull(): Unit = {
        handshakeState match {
          case Completed ⇒
            pendingMessage match {
              case OptionVal.None ⇒
                if (!hasBeenPulled(in))
                  pull(in)
              case OptionVal.Some(p) ⇒
                push(out, p)
                pendingMessage = OptionVal.None
            }

          case Start ⇒
            val uniqueRemoteAddress = outboundContext.associationState.uniqueRemoteAddress
            if (uniqueRemoteAddress.isCompleted) {
              handshakeCompleted()
            } else {
              // will pull when handshake reply is received (uniqueRemoteAddress completed)
              handshakeState = ReqInProgress
              schedulePeriodically(HandshakeRetryTick, retryInterval)

              // The InboundHandshake stage will complete the uniqueRemoteAddress future
              // when it receives the HandshakeRsp reply
              implicit val ec = materializer.executionContext
              uniqueRemoteAddress.foreach {
                getAsyncCallback[UniqueAddress] { _ ⇒
                  if (handshakeState != Completed) {
                    handshakeCompleted()
                    if (isAvailable(out))
                      pull(in)
                  }
                }.invoke
              }
            }

            // always push a HandshakeReq as the first message
            pushHandshakeReq()

          case ReqInProgress ⇒ // will pull when handshake reply is received
        }
      }

      private def pushHandshakeReq(): Unit = {
        injectHandshakeTickScheduled = true
        scheduleOnce(InjectHandshakeTick, injectHandshakeInterval)
        outboundContext.associationState.lastUsedTimestamp.set(System.nanoTime())
        if (isAvailable(out))
          push(out, createHandshakeReqEnvelope())
      }

      private def pushLivenessProbeReq(): Unit = {
        // The associationState.lastUsedTimestamp will be updated when the HandshakeRsp is received
        // and that is the confirmation that the other system is alive, and will not be quarantined
        // by the quarantine-idle-outbound-after even though no real messages have been sent.
        if (handshakeState == Completed && isAvailable(out) && pendingMessage.isEmpty) {
          val lastUsedDuration = (System.nanoTime() - outboundContext.associationState.lastUsedTimestamp.get()).nanos
          if (lastUsedDuration >= livenessProbeInterval) {
            log.info(
              "Association to [{}] has been idle for [{}] seconds, sending HandshakeReq to validate liveness",
              outboundContext.remoteAddress, lastUsedDuration.toSeconds)
            push(out, createHandshakeReqEnvelope())
          }
        }
      }

      private def createHandshakeReqEnvelope(): OutboundEnvelope = {
        outboundEnvelopePool.acquire().init(
          recipient = OptionVal.None, message = HandshakeReq(outboundContext.localAddress, outboundContext.remoteAddress), sender = OptionVal.None)
      }

      private def handshakeCompleted(): Unit = {
        handshakeState = Completed
        cancelTimer(HandshakeRetryTick)
        cancelTimer(HandshakeTimeout)
      }

      override protected def onTimer(timerKey: Any): Unit =
        timerKey match {
          case InjectHandshakeTick ⇒
            // next onPush message will trigger sending of HandshakeReq
            injectHandshakeTickScheduled = false
          case LivenessProbeTick ⇒
            pushLivenessProbeReq()
          case HandshakeRetryTick ⇒
            if (isAvailable(out))
              pushHandshakeReq()
          case HandshakeTimeout ⇒
            failStage(new HandshakeTimeoutException(
              s"Handshake with [${outboundContext.remoteAddress}] did not complete within ${timeout.toMillis} ms"))
        }

      setHandlers(in, out, this)
    }

}

/**
 * INTERNAL API
 */
private[remote] class InboundHandshake(inboundContext: InboundContext, inControlStream: Boolean) extends GraphStage[FlowShape[InboundEnvelope, InboundEnvelope]] {
  val in: Inlet[InboundEnvelope] = Inlet("InboundHandshake.in")
  val out: Outlet[InboundEnvelope] = Outlet("InboundHandshake.out")
  override val shape: FlowShape[InboundEnvelope, InboundEnvelope] = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new TimerGraphStageLogic(shape) with OutHandler with StageLogging {
      import OutboundHandshake._

      // InHandler
      if (inControlStream)
        setHandler(in, new InHandler {
          override def onPush(): Unit = {
            val env = grab(in)
            env.message match {
              case HandshakeReq(from, to) ⇒ onHandshakeReq(from, to)
              case HandshakeRsp(from) ⇒
                // Touch the lastUsedTimestamp here also because when sending the extra low frequency HandshakeRsp
                // the timestamp is not supposed to be updated when sending but when receiving reply, which confirms
                // that the other system is alive.
                inboundContext.association(from.address).associationState.lastUsedTimestamp.set(System.nanoTime())

                after(inboundContext.completeHandshake(from)) {
                  pull(in)
                }
              case _ ⇒
                onMessage(env)
            }
          }
        })
      else
        setHandler(in, new InHandler {
          override def onPush(): Unit = {
            val env = grab(in)
            env.message match {
              case HandshakeReq(from, to) ⇒ onHandshakeReq(from, to)
              case _ ⇒
                onMessage(env)
            }
          }
        })

      private def onHandshakeReq(from: UniqueAddress, to: Address): Unit = {
        if (to == inboundContext.localAddress.address) {
          after(inboundContext.completeHandshake(from)) {
            inboundContext.sendControl(from.address, HandshakeRsp(inboundContext.localAddress))
            pull(in)
          }
        } else {
          log.warning(
            "Dropping Handshake Request from [{}] addressed to unknown local address [{}]. " +
              "Local address is [{}]. Check that the sending system uses the same " +
              "address to contact recipient system as defined in the " +
              "'akka.remote.artery.canonical.hostname' of the recipient system. " +
              "The name of the ActorSystem must also match.",
            from, to, inboundContext.localAddress.address)

          pull(in)
        }
      }

      private def after(first: Future[Done])(thenInside: ⇒ Unit): Unit = {
        first.value match {
          case Some(_) ⇒
            // This in the normal case (all but the first). The future will be completed
            // because handshake was already completed. Note that we send those HandshakeReq
            // periodically.
            thenInside
          case None ⇒
            implicit val ec = materializer.executionContext
            first.onComplete { _ ⇒
              getAsyncCallback[Done](_ ⇒ thenInside).invoke(Done)
            }
        }

      }

      private def onMessage(env: InboundEnvelope): Unit = {
        if (isKnownOrigin(env))
          push(out, env)
        else {
          if (log.isDebugEnabled)
            log.debug(
              s"Dropping message [{}] from unknown system with UID [{}]. " +
                "This system with UID [{}] was probably restarted. " +
                "Messages will be accepted when new handshake has been completed.",
              env.message.getClass.getName, env.originUid, inboundContext.localAddress.uid)
          pull(in)
        }
      }

      private def isKnownOrigin(env: InboundEnvelope): Boolean = {
        // the association is passed in the envelope from the Decoder stage to avoid
        // additional lookup. The second OR case is because if we didn't use fusing it
        // would be possible that it was not found by Decoder (handshake not completed yet)
        env.association.isDefined || inboundContext.association(env.originUid).isDefined
      }

      // OutHandler
      override def onPull(): Unit = pull(in)

      setHandler(out, this)

    }

}
