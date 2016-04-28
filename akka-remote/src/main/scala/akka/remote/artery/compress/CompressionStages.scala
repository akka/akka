/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.compress

import akka.remote.EndpointManager.Send
import akka.remote.artery.compress.CompressionProtocol.CompressionAdvertisement
import akka.remote.artery.{ ArteryTransport, InboundEnvelope, OutboundContext }
import akka.stream.Attributes
import akka.stream.impl.fusing.GraphStages.SimpleLinearGraphStage
import akka.stream.stage.{ InHandler, OutHandler, GraphStageLogic }

final class OutboundCompression(outboundContext: OutboundContext) extends SimpleLinearGraphStage[Send] {

  override def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape) with InHandler with OutHandler {
    override def onPush(): Unit = ???
    override def onPull(): Unit = ???

    setHandlers(in, out, this)
  }
}

// TODO attempt not depending on entire transport class?
final class InboundCompression(transport: ArteryTransport) extends SimpleLinearGraphStage[InboundEnvelope] {

  override def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape) with InHandler with OutHandler {
    override def onPush(): Unit = {
      val envelope = grab(in)
      envelope.message match {
        case c: CompressionAdvertisement ⇒
          ???
      }
    }
    override def onPull(): Unit = pull(in)

    setHandlers(in, out, this)
  }
}
