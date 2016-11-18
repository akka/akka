/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.io

import akka.stream.{ ActorMaterializer, Attributes, ThrottleMode }
import akka.stream.impl.io.ByteStringParser
import akka.stream.scaladsl.{ Sink, Source }
import akka.stream.stage.GraphStageLogic
import akka.stream.testkit.StreamSpec
import akka.util.ByteString

import scala.concurrent.Await
import scala.concurrent.duration._

class ByteStringParserSpec extends StreamSpec() {
  implicit val materializer = ActorMaterializer()

  class Chunker extends ByteStringParser[ByteString] {
    import ByteStringParser._

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new ParsingLogic {

      lazy val step: ParseStep[ByteString] = new ParseStep[ByteString] {
        override def parse(reader: ByteReader): ParseResult[ByteString] = {
          val bytes = reader.take(2)
          ParseResult(Some(bytes), step)
        }
      }

      startWith(step)

    }

  }

  "ByteStringParser" must {

    "respect backpressure" in {
      // The Chunker produces two frames for one incoming 4 byte chunk. Hence, the rate in the incoming
      // side of the Chunker should only be half than on its outgoing side.

      val result = Source.repeat(ByteString("abcd"))
        .take(500)
        .throttle(1000, 1.second, 10, ThrottleMode.Enforcing)
        .via(new Chunker)
        .throttle(1000, 1.second, 10, ThrottleMode.Shaping)
        .runWith(Sink.ignore)

      Await.result(result, 5.seconds)

    }

  }

}
