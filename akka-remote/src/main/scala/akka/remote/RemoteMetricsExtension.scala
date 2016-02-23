/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote

import java.util.concurrent.ConcurrentHashMap
import scala.annotation.tailrec
import akka.actor.ActorSelectionMessage
import akka.actor.ActorSystem
import akka.actor.ExtendedActorSystem
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider
import akka.event.Logging
import akka.routing.RouterEnvelope

/**
 * INTERNAL API
 * Extension that keeps track of remote metrics, such
 * as max size of different message types.
 */
private[akka] object RemoteMetricsExtension extends ExtensionId[RemoteMetrics] with ExtensionIdProvider {
  override def get(system: ActorSystem): RemoteMetrics = super.get(system)

  override def lookup = RemoteMetricsExtension

  override def createExtension(system: ExtendedActorSystem): RemoteMetrics =
    if (system.settings.config.getString("akka.remote.log-frame-size-exceeding").toLowerCase == "off")
      new RemoteMetricsOff
    else
      new RemoteMetricsOn(system)
}

/**
 * INTERNAL API
 */
private[akka] trait RemoteMetrics extends Extension {
  /**
   * Logging of the size of different message types.
   * Maximum detected size per message type is logged once, with
   * and increase threshold of 10%.
   */
  def logPayloadBytes(msg: Any, payloadBytes: Int): Unit
}

/**
 * INTERNAL API
 */
private[akka] class RemoteMetricsOff extends RemoteMetrics {
  override def logPayloadBytes(msg: Any, payloadBytes: Int): Unit = ()
}

/**
 * INTERNAL API
 */
private[akka] class RemoteMetricsOn(system: ExtendedActorSystem) extends RemoteMetrics {

  private val logFrameSizeExceeding: Int = system.settings.config.getBytes(
    "akka.remote.log-frame-size-exceeding").toInt
  private val log = Logging(system, this.getClass)
  private val maxPayloadBytes: ConcurrentHashMap[Class[_], Integer] = new ConcurrentHashMap

  override def logPayloadBytes(msg: Any, payloadBytes: Int): Unit =
    if (payloadBytes >= logFrameSizeExceeding) {
      val clazz = msg match {
        case x: ActorSelectionMessage ⇒ x.msg.getClass
        case x: RouterEnvelope        ⇒ x.message.getClass
        case _                        ⇒ msg.getClass
      }

      // 10% threshold until next log
      def newMax = (payloadBytes * 1.1).toInt

      @tailrec def check(): Unit = {
        val max = maxPayloadBytes.get(clazz)
        if (max eq null) {
          if (maxPayloadBytes.putIfAbsent(clazz, newMax) eq null)
            log.info("Payload size for [{}] is [{}] bytes", clazz.getName, payloadBytes)
          else check()
        } else if (payloadBytes > max) {
          if (maxPayloadBytes.replace(clazz, max, newMax))
            log.info("New maximum payload size for [{}] is [{}] bytes", clazz.getName, payloadBytes)
          else check()
        }
      }
      check()
    }
}

