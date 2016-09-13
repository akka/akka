package akka.http.impl.http2

import akka.stream.Attributes
import akka.stream.impl.io.ByteStringParser
import akka.stream.stage.GraphStageLogic

object FrameParser extends ByteStringParser[FrameEvent] {
  import ByteStringParser._

  abstract class Step extends ParseStep[FrameEvent]

  def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new ParsingLogic {
      startWith(new ReadFrame)

      class ReadFrame extends Step {
        def parse(reader: ByteReader): ParseResult[FrameEvent] = {
          println(s"Have ${reader.remainingData} remaining")
          throw NeedMoreData
        }
      }
    }
}
