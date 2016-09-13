package akka.http.impl.http2

import akka.stream.Attributes
import akka.stream.impl.io.ByteStringParser
import akka.stream.stage.GraphStageLogic

object FrameParser extends ByteStringParser[FrameEvent] {
  import ByteStringParser._

  def createLogic(inheritedAttributes: Attributes): GraphStageLogic = ???
}
