/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.annotation.InternalApi
import akka.http.scaladsl.settings.Http2ServerSettings

/** INTERNAL API */
@InternalApi
private[http2] trait IncomingFlowController {
  def onConnectionDataReceived(outstandingConnectionLevelWindow: Int, totalBufferedData: Int): Int

  def onStreamDataDispatched(outstandingConnectionLevelWindow: Int, totalBufferedData: Int,
                             outstandingStreamLevelWindow: Int, streamBufferedData: Int): IncomingFlowController.WindowIncrements
}
private[http2] object IncomingFlowController {
  /** INTERNAL API */
  @InternalApi
  private[http2] case class WindowIncrements(connectionLevel: Int, streamLevel: Int) {
    require(connectionLevel >= 0, s"Connection-level window increment must be >= 0 but was $connectionLevel")
    require(streamLevel >= 0, s"Stream-level window increment must be >= 0 but was $streamLevel")
  }
  private[http2] object WindowIncrements {
    val NoIncrements = WindowIncrements(0, 0)
  }

  def default(settings: Http2ServerSettings): IncomingFlowController =
    default(settings.incomingConnectionLevelBufferSize, settings.incomingStreamLevelBufferSize)

  /** The default scheme sends out WINDOW_UPDATE frames when buffered + outstanding data falls below half of the maximum configured size */
  def default(maximumConnectionLevelWindow: Int, maximumStreamLevelWindow: Int): IncomingFlowController =
    new IncomingFlowController {
      def onConnectionDataReceived(outstandingConnectionLevelWindow: Int, totalBufferedData: Int): Int =
        ifMoreThanHalfUsed(maximumConnectionLevelWindow, outstandingConnectionLevelWindow, totalBufferedData)

      def onStreamDataDispatched(outstandingConnectionLevelWindow: Int, totalBufferedData: Int, outstandingStreamLevelWindow: Int, streamBufferedData: Int): WindowIncrements =
        WindowIncrements(
          onConnectionDataReceived(outstandingConnectionLevelWindow, totalBufferedData),
          ifMoreThanHalfUsed(maximumStreamLevelWindow, outstandingStreamLevelWindow, streamBufferedData)
        )

      private def ifMoreThanHalfUsed(max: Int, outstanding: Int, buffered: Int): Int = {
        val totalReservedSpace = outstanding + buffered
        if (totalReservedSpace < max / 2) max - totalReservedSpace
        else 0
      }
    }
}