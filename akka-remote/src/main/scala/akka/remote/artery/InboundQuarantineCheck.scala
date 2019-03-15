/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery

import akka.stream.Attributes
import akka.stream.FlowShape
import akka.stream.Inlet
import akka.stream.Outlet
import akka.stream.stage._
import akka.remote.UniqueAddress
import akka.util.OptionVal
import akka.event.Logging
import akka.remote.HeartbeatMessage
import akka.actor.ActorSelectionMessage

/**
 * INTERNAL API
 */
private[remote] class InboundQuarantineCheck(inboundContext: InboundContext)
    extends GraphStage[FlowShape[InboundEnvelope, InboundEnvelope]] {
  val in: Inlet[InboundEnvelope] = Inlet("InboundQuarantineCheck.in")
  val out: Outlet[InboundEnvelope] = Outlet("InboundQuarantineCheck.out")
  override val shape: FlowShape[InboundEnvelope, InboundEnvelope] = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler with StageLogging {

      override protected def logSource = classOf[InboundQuarantineCheck]

      // InHandler
      override def onPush(): Unit = {
        val env = grab(in)
        env.association match {
          case OptionVal.None =>
            // unknown, handshake not completed
            push(out, env)
          case OptionVal.Some(association) =>
            if (association.associationState.isQuarantined(env.originUid)) {
              if (log.isDebugEnabled)
                log.debug(
                  "Dropping message [{}] from [{}#{}] because the system is quarantined",
                  Logging.messageClassName(env.message),
                  association.remoteAddress,
                  env.originUid)
              // avoid starting outbound stream for heartbeats
              if (!env.message.isInstanceOf[Quarantined] && !isHeartbeat(env.message))
                inboundContext.sendControl(
                  association.remoteAddress,
                  Quarantined(inboundContext.localAddress, UniqueAddress(association.remoteAddress, env.originUid)))
              pull(in)
            } else
              push(out, env)
        }
      }

      private def isHeartbeat(msg: Any): Boolean = msg match {
        case _: HeartbeatMessage                              => true
        case ActorSelectionMessage(_: HeartbeatMessage, _, _) => true
        case _                                                => false
      }

      // OutHandler
      override def onPull(): Unit = pull(in)

      setHandlers(in, out, this)
    }
}
