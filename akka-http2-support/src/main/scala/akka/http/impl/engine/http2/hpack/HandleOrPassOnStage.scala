/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2.hpack

import akka.annotation.InternalApi
import akka.stream.{ FlowShape, Inlet, Outlet, Shape }
import akka.stream.stage.{ GraphStageLogic, InHandler, OutHandler }

/**
 * INTERNAL API
 */
@InternalApi
private[http2] abstract class HandleOrPassOnStage[T <: U, U](shape: FlowShape[T, U]) extends GraphStageLogic(shape) {
  private def in: Inlet[T] = shape.in
  private def out: Outlet[U] = shape.out

  def become(state: State): Unit = setHandlers(in, out, state)
  abstract class State extends InHandler with OutHandler {
    val handleEvent: PartialFunction[T, Unit]

    def onPush(): Unit = {
      val event = grab(in)
      handleEvent.applyOrElse[T, Unit](event, ev ⇒ push(out, ev))
    }
    def onPull(): Unit = pull(in)
  }
}
