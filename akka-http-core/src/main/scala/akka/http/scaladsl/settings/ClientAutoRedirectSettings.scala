/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.settings

import java.util

import akka.http.impl.settings.ClientAutoRedirectSettingsImpl
import com.typesafe.config.Config

import scala.collection.immutable
import scala.collection.JavaConverters._
import scala.annotation.varargs

abstract class ClientAutoRedirectSettings private[akka] () extends akka.http.javadsl.settings.ClientAutoRedirectSettings { self: ClientAutoRedirectSettingsImpl ⇒
  def sameOrigin: akka.http.javadsl.settings.ClientAutoRedirectSettingsItem
  def crossOrigin: akka.http.javadsl.settings.ClientAutoRedirectSettingsItem

  /* JAVA APIs */

  final override def getSameOrigin: akka.http.javadsl.settings.ClientAutoRedirectSettingsItem = sameOrigin
  final override def getCrossOrigin: akka.http.javadsl.settings.ClientAutoRedirectSettingsItem = crossOrigin

  // ---

  // overrides for more specific return type
  override def withSameOrigin(newValue: akka.http.javadsl.settings.ClientAutoRedirectSettingsItem): ClientAutoRedirectSettings = self.copy(sameOrigin = newValue)
  override def withCrossOrigin(newValue: akka.http.javadsl.settings.ClientAutoRedirectSettingsItem): ClientAutoRedirectSettings = self.copy(crossOrigin = newValue)
}

object ClientAutoRedirectSettings extends SettingsCompanion[ClientAutoRedirectSettings] {
  trait HeadersForwardMode extends akka.http.javadsl.settings.ClientAutoRedirectSettings.HeadersForwardMode
  object HeadersForwardMode {
    case object All extends HeadersForwardMode
    case object Zero extends HeadersForwardMode
    case class Only(which: immutable.Seq[String]) extends HeadersForwardMode {
      /** Java API */
      def getWhich: util.List[String] = which.asJava
    }
    object Only {
      /** Java API */
      @varargs def create(allowed: String*) = new Only(akka.japi.Util.immutableSeq(allowed.asJava))
    }

    def apply(strings: immutable.Seq[String]): HeadersForwardMode = {
      val res = strings.toList match {
        case Nil      ⇒ Zero
        case "*" :: _ ⇒ All
        case s        ⇒ Only(s)
      }
      res
    }
  }

  override def apply(config: Config): ClientAutoRedirectSettings = ClientAutoRedirectSettingsImpl(config)
  override def apply(configOverrides: String): ClientAutoRedirectSettings = ClientAutoRedirectSettingsImpl(configOverrides)
}
