/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.query.journal.leveldb

import java.util

import akka.annotation.InternalApi
import akka.stream.Outlet
import akka.stream.stage.GraphStageLogic
import akka.util.ccompat.JavaConverters._

/**
 * INTERNAL API
 */
@InternalApi
private[leveldb] trait Buffer[T] { self: GraphStageLogic =>
  private val buf: java.util.LinkedList[T] = new util.LinkedList[T]()
  def buffer(element: T): Unit = {
    buf.add(element)
  }
  def buffer(all: Set[T]): Unit = {
    buf.addAll(all.asJava)
  }
  def deliverBuf(out: Outlet[T]): Unit = {
    if (!buf.isEmpty && isAvailable(out)) {
      push(out, buf.remove())
    }
  }
  def bufferSize: Int = buf.size
  def bufferEmpty: Boolean = buf.isEmpty
}
