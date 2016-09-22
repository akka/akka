/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.stream.Outlet
import akka.stream.stage.GraphStageLogic
import akka.stream.stage.OutHandler

trait BufferedOutletSupport { logic: GraphStageLogic ⇒
  trait GenericOutlet[T] {
    def setHandler(handler: OutHandler): Unit
    def push(elem: T): Unit
  }
  object GenericOutlet {
    implicit def fromSubSourceOutlet[T](subSourceOutlet: SubSourceOutlet[T]): GenericOutlet[T] =
      new GenericOutlet[T] {
        def setHandler(handler: OutHandler): Unit = subSourceOutlet.setHandler(handler)
        def push(elem: T): Unit = subSourceOutlet.push(elem)
      }
    implicit def fromOutlet[T](outlet: Outlet[T]): GenericOutlet[T] =
      new GenericOutlet[T] {
        def setHandler(handler: OutHandler): Unit = logic.setHandler(outlet, handler)
        def push(elem: T): Unit = logic.emit(outlet, elem)
      }
  }
  class BufferedOutlet[T](outlet: GenericOutlet[T]) extends OutHandler {
    val buffer: java.util.ArrayDeque[T] = new java.util.ArrayDeque[T]
    var pulled: Boolean = false

    def doPush(elem: T): Unit = outlet.push(elem)

    def onPull(): Unit =
      if (!buffer.isEmpty) doPush(buffer.pop())
      else pulled = true

    outlet.setHandler(this)

    def push(elem: T): Unit =
      if (pulled) {
        doPush(elem)
        pulled = false
      } else buffer.addLast(elem)
  }
}