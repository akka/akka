/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.ddata

import akka.annotation.InternalApi
import akka.cluster.ddata.Key.KeyId
import akka.event.LoggingAdapter

/**
 * INTERNAL API
 *
 * This class is not thread-safe. It is supposed to be used from an actor.
 */
@InternalApi private[akka] class PayloadSizeAggregator(
    log: LoggingAdapter,
    logSizeExceeding: Int,
    warnSizeExceeding: Int) {
  private var maxPayloadBytes: Map[String, Int] = Map.empty

  def updatePayloadSize(key: KeyId, size: Int): Unit = {
    if (size >= logSizeExceeding) {
      // 10% threshold until next log
      def newMax = (size * 1.1).toInt

      def logSize(): Unit = {
        if (size >= warnSizeExceeding)
          log.warning(
            "Distributed data size for [{}] is [{}] bytes. Close to max remote message payload size.",
            key,
            size)
        else
          log.info("Distributed data size for [{}] is [{}] bytes.", key, size)
      }

      maxPayloadBytes.get(key) match {
        case Some(max) =>
          if (size > max) {
            maxPayloadBytes = maxPayloadBytes.updated(key, newMax)
            logSize()
          }
        case None =>
          maxPayloadBytes = maxPayloadBytes.updated(key, newMax)
          logSize()
      }
    }
  }

  def remove(key: KeyId): Unit =
    maxPayloadBytes -= key

}
