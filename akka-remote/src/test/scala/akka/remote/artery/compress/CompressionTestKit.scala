/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.compress

/* INTERNAL API */
private[remote] trait CompressionTestKit {
  def assertCompression[T](table: CompressionTable[T], id: Int, assertion: T ⇒ Unit): Unit = {
    table.map.find(_._2 == id)
      .orElse { throw new AssertionError(s"No key was compressed to the id [$id]! Table was: $table") }
      .foreach(i ⇒ assertion(i._1))
  }
}

/* INTERNAL API */
private[remote] object CompressionTestKit extends CompressionTestKit
