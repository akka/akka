/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http
package scaladsl
package unmarshalling
package sse

import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.stream.scaladsl.{ Sink, Source }
import org.scalatest.{ AsyncWordSpec, Matchers }

final class ServerSentEventParserSpec extends AsyncWordSpec with Matchers with BaseUnmarshallingSpec {

  "A ServerSentEventParser" should {
    "parse ServerSentEvents correctly" in {
      val input = """|data: event 1 line 1
                     |data:event 1 line 2
                     |
                     |data: event 2
                     |:This is a comment and must be ignored
                     |ignore: this is an ignored field
                     |event: Only the last event should be considered
                     |event: event 2 event
                     |id: Only the last id should be considered
                     |id: 42
                     |retry: 123
                     |retry: 512
                     |
                     |
                     |event
                     |:no data means event gets ignored
                     |
                     |data
                     |:emtpy data means event gets ignored
                     |
                     |data:
                     |:emtpy data means event gets ignored
                     |
                     |data: event 3
                     |id
                     |event
                     |retry
                     |:empty id is possible
                     |:empty event is ignored
                     |:empty retry is ignored
                     |
                     |data: event 4
                     |event:
                     |retry: not numeric
                     |:empty event is ignored
                     |:invalid retry is ignored
                     |
                     |data: incomplete
                     |""".stripMargin
      Source(input.split(f"%n").toVector)
        .via(new ServerSentEventParser(1048576))
        .runWith(Sink.seq)
        .map(
          _ shouldBe Vector(
            ServerSentEvent("event 1 line 1\nevent 1 line 2"),
            ServerSentEvent("event 2", Some("event 2 event"), Some("42"), Some(512)),
            ServerSentEvent("event 3", None, Some("")),
            ServerSentEvent("event 4")
          )
        )
    }
  }
}
