/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http
package scaladsl
package unmarshalling
package sse

import akka.annotation.InternalApi
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }
import akka.stream.{ Attributes, FlowShape, Inlet, Outlet }

/** INTERNAL API */
@InternalApi
private object ServerSentEventParser {

  final object PosInt {
    def unapply(s: String): Option[Int] =
      try { Some(s.trim.toInt) } catch { case _: NumberFormatException ⇒ None }
  }

  final class Builder {

    private var data = Vector.empty[String]

    private var eventType = Option.empty[String]

    private var id = Option.empty[String]

    private var retry = Option.empty[Int]

    private var _size = 0

    def appendData(value: String): Unit = {
      _size += 5 + value.length
      data :+= value
    }

    def setType(value: String): Unit = {
      val oldSize = eventType.fold(0)(6 + _.length)
      _size += 6 + value.length - oldSize
      eventType = Some(value)
    }

    def setId(value: String): Unit = {
      val oldSize = id.fold(0)(3 + _.length)
      _size += 3 + value.length - oldSize
      id = Some(value)
    }

    def setRetry(value: Int, length: Int): Unit = {
      val oldSize = retry.fold(0)(6 + _.toString.length)
      _size += 6 + length - oldSize
      retry = Some(value)
    }

    def hasData: Boolean =
      data.nonEmpty

    def size: Int =
      _size

    def build(): ServerSentEvent =
      ServerSentEvent(data.mkString("\n"), eventType, id, retry)

    def reset(): Unit = {
      data = Vector.empty[String]
      eventType = None
      id = None
      retry = None
      _size = 0
    }
  }

  private final val Data = "data"

  private final val EventType = "event"

  private final val Id = "id"

  private final val Retry = "retry"

  private val Field = """([^:]+): ?(.*)""".r
}

/** INTERNAL API */
@InternalApi
private final class ServerSentEventParser(maxEventSize: Int) extends GraphStage[FlowShape[String, ServerSentEvent]] {

  override val shape =
    FlowShape(Inlet[String]("ServerSentEventParser.in"), Outlet[ServerSentEvent]("ServerSentEventParser.out"))

  override def createLogic(attributes: Attributes) =
    new GraphStageLogic(shape) with InHandler with OutHandler {
      import ServerSentEventParser._
      import shape._

      private val builder = new Builder()

      setHandlers(in, out, this)

      override def onPush() = {
        val line = grab(in)
        if (line == "") { // An event is terminated with a new line
          if (builder.hasData) // Events without data are ignored according to the spec
            push(out, builder.build())
          else
            pull(in)
          builder.reset()
        } else if (builder.size + line.length <= maxEventSize) {
          line match {
            case Id                                    ⇒ builder.setId("")
            case Field(Data, data) if data.nonEmpty    ⇒ builder.appendData(data)
            case Field(EventType, t) if t.nonEmpty     ⇒ builder.setType(t)
            case Field(Id, id)                         ⇒ builder.setId(id)
            case Field(Retry, s @ PosInt(r)) if r >= 0 ⇒ builder.setRetry(r, s.length)
            case _                                     ⇒ // ignore according to spec
          }
          pull(in)
        } else {
          failStage(new IllegalStateException(s"maxEventSize of $maxEventSize exceeded!"))
          builder.reset()
        }
      }

      override def onPull() = pull(in)
    }
}
