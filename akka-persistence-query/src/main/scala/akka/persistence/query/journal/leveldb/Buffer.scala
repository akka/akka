/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.query.journal.leveldb

import akka.annotation.InternalApi
import akka.stream.Outlet
import akka.stream.stage.GraphStageLogic

/**
 * INTERNAL API
 */
@InternalApi
private[leveldb] trait Buffer[T] { self: GraphStageLogic =>
  private var buf: Vector[T] = Vector.empty[T]
  def buffer(element: T): Unit = {
    buf :+= element
  }
  def buffer(all: Set[T]): Unit = {
    buf ++= all
  }
  def deliverBuf(out: Outlet[T]): Unit = {
    if (buf.nonEmpty && isAvailable(out)) {
      val next = buf.head
      push(out, next)
      buf = buf.tail
    } else {}
  }
  def bufferSize: Int = buf.size
  def bufferEmpty: Boolean = buf.isEmpty
}
