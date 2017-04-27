/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.NotUsed
import akka.annotation.InternalApi
import akka.http.impl.engine.http2.Http2Protocol.ErrorCode
import akka.http.scaladsl.model.http2.PeerClosedStreamException
import akka.stream.scaladsl.Source
import akka.stream.stage.{ GraphStageLogic, StageLogging }
import akka.util.ByteString

import scala.collection.immutable

/** INTERNAL API */
@InternalApi
private[http2] trait Http2StreamHandling { self: GraphStageLogic with GenericOutletSupport with StageLogging ⇒
  // required API from demux
  def multiplexer: Http2Multiplexer
  def pushGOAWAY(errorCode: ErrorCode, debug: String): Unit
  def dispatchSubstream(sub: Http2SubStream): Unit

  private var incomingStreams = new immutable.TreeMap[Int, IncomingStreamState]
  private var largestIncomingStreamId = 0
  private def streamFor(streamId: Int): IncomingStreamState =
    incomingStreams.get(streamId) match {
      case Some(state) ⇒ state
      case None ⇒
        if (streamId <= largestIncomingStreamId) Closed // closed streams are never put into the map
        else {
          largestIncomingStreamId = streamId
          incomingStreams += streamId → Idle
          Idle
        }
    }
  def handleStreamEvent(e: StreamFrameEvent): Unit = {
    val newState = streamFor(e.streamId).handle(e)
    if (newState == Closed) incomingStreams -= e.streamId
    else incomingStreams += e.streamId → newState
  }
  def resetStream(streamId: Int, errorCode: ErrorCode): Unit = {
    // FIXME: put this stream into an extra state where we allow some frames still to be received
    incomingStreams -= streamId
    multiplexer.pushControlFrame(RstStreamFrame(streamId, errorCode))
  }

  sealed abstract class IncomingStreamState { _: Product ⇒
    def handle(event: StreamFrameEvent): IncomingStreamState

    def stateName: String = productPrefix
    def receivedUnexpectedFrame(e: StreamFrameEvent): IncomingStreamState = {
      pushGOAWAY(ErrorCode.PROTOCOL_ERROR, s"Received unexpected frame of type ${e.frameTypeName} for stream ${e.streamId} in state $stateName")
      Closed
    }
  }
  case object Idle extends IncomingStreamState {
    def handle(event: StreamFrameEvent): IncomingStreamState = event match {
      case frame @ ParsedHeadersFrame(streamId, endStream, headers, prioInfo) ⇒
        val (data: Source[ByteString, NotUsed], nextState) =
          if (endStream) (Source.empty, HalfClosedRemote)
          else {
            val subSource = new SubSourceOutlet[ByteString](s"substream-out-$streamId")
            (Source.fromGraph(subSource.source), Open(new BufferedOutlet[ByteString](subSource) {
              override def onDownstreamFinish(): Unit =
                // FIXME: when substream (= request entity) is cancelled, we need to RST_STREAM
                // if the stream is finished and sent a RST_STREAM we can just remove the incoming stream from our map
                incomingStreams -= streamId
            }))
          }

        // FIXME: after multiplexer PR is merged
        // prioInfo.foreach(multiplexer.updatePriority)
        dispatchSubstream(Http2SubStream(frame, data))
        nextState

      case x ⇒ receivedUnexpectedFrame(x)
    }
  }
  sealed abstract class ReceivingData(outlet: BufferedOutlet[ByteString], afterEndStreamReceived: IncomingStreamState) extends IncomingStreamState { _: Product ⇒
    def handle(event: StreamFrameEvent): IncomingStreamState = event match {
      case d: DataFrame ⇒
        outlet.push(d.payload)
        maybeFinishStream(d.endStream)
      case r: RstStreamFrame ⇒
        outlet.fail(new PeerClosedStreamException(r.streamId, r.errorCode))
        multiplexer.cancelSubStream(r.streamId)
        Closed

      case h: ParsedHeadersFrame ⇒
        // ignored
        log.debug(s"Ignored intermediate HEADERS frame: $h")

        maybeFinishStream(h.endStream)
    }

    protected def maybeFinishStream(endStream: Boolean): IncomingStreamState =
      if (endStream) {
        outlet.complete()
        afterEndStreamReceived
      } else this
  }
  // on the incoming side there's (almost) no difference between Open and HalfClosedLocal
  case class Open(outlet: BufferedOutlet[ByteString]) extends ReceivingData(outlet, HalfClosedRemote)
  case class HalfClosedLocal(outlet: BufferedOutlet[ByteString]) extends ReceivingData(outlet, Closed)
  case object HalfClosedRemote extends IncomingStreamState {
    def handle(event: StreamFrameEvent): IncomingStreamState = event match {
      case r: RstStreamFrame ⇒
        multiplexer.cancelSubStream(r.streamId)
        Closed
      case _ ⇒ receivedUnexpectedFrame(event)
    }
  }
  case object Closed extends IncomingStreamState {
    def handle(event: StreamFrameEvent): IncomingStreamState = receivedUnexpectedFrame(event)
  }

  // needed once PUSH_PROMISE support was added
  //case object ReservedLocal extends IncomingStreamState
  //case object ReservedRemote extends IncomingStreamState
}
