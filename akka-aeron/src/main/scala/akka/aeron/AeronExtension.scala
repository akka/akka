/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.aeron

import akka.actor.{ ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider }
import akka.annotation.InternalApi
import akka.event.{ Logging, LoggingAdapter }
import com.typesafe.config.Config
import io.aeron.{ Aeron, AvailableImageHandler, Image, UnavailableImageHandler }

sealed trait MediaDriverLocation
case object Embedded extends MediaDriverLocation
final case class External(location: String) extends MediaDriverLocation

/**
 * INTERNAL API
 */
@InternalApi private[akka] object AeronSettings {

  def apply(config: Config): AeronSettings = {
    new AeronSettings(
      config.getDuration("connection.driver-timeout").toMillis,
      config.getString("connection.aeron-directory") match {
        case "<embedded>" ⇒ Embedded
        case other        ⇒ External(other)
      }
    )
  }
}

/**
 * @param driverTimeoutMs Timeout for communicating with the MediaDriver
 * @param mediaDriverLocation Aeron directory location for communicating with the MediaDriver
 */
class AeronSettings private (
  val driverTimeoutMs:     Long,
  val mediaDriverLocation: MediaDriverLocation
)

/**
 * INTERNAL API
 */
@InternalApi
private class AeronExtensionImpl(eas: ExtendedActorSystem) extends AeronExtension {

  private val log: LoggingAdapter = Logging(eas, getClass)

  private val settings: AeronSettings = AeronSettings(eas.settings.config.getConfig("akka.aeron"))

  val aeron: Aeron = {
    val aeronContext: Aeron.Context = {

      val aeronDirectoryName: String = settings.mediaDriverLocation match {
        case Embedded           ⇒ MediaDriverExtension(eas).aeronDirectory
        case External(location) ⇒ location
      }
      new Aeron.Context()
        .driverTimeoutMs(settings.driverTimeoutMs)
        .availableImageHandler(new AvailableImageHandler {
          override def onAvailableImage(img: Image): Unit = {
            if (log.isDebugEnabled)
              log.debug(s"onAvailableImage from ${img.sourceIdentity} session ${img.sessionId}")
          }
        })
        .unavailableImageHandler(new UnavailableImageHandler {
          override def onUnavailableImage(img: Image): Unit = {
            if (log.isDebugEnabled)
              log.debug(s"onUnavailableImage from ${img.sourceIdentity} session ${img.sessionId}")
          }
        })
        .aeronDirectoryName(aeronDirectoryName)
    }

    Aeron.connect(aeronContext)
  }
}

abstract class AeronExtension extends Extension {
  def aeron: Aeron
}
/**
 * Not used by Artery as it has different requirements for error handlers
 * TODO how to fail streams if there is a fatal Aeron error? Global kill switch?
 * Should losing connection to the local media driver result in a full system restart?
 * is a single aeron connection actually a good idea?
 */
object AeronExtension extends ExtensionId[AeronExtension] with ExtensionIdProvider {

  override def createExtension(system: ExtendedActorSystem): AeronExtension = {
    new AeronExtensionImpl(system)
  }

  override def lookup(): ExtensionId[_ <: Extension] = AeronExtension

  // TODO if remain public add Java API
}
