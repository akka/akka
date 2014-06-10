/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import language.implicitConversions
import com.typesafe.config.Config
import akka.actor.{ ActorRefFactory, ActorContext, ActorSystem }

package object util {

  private[http] def actorSystem(implicit refFactory: ActorRefFactory): ActorSystem =
    refFactory match {
      case x: ActorContext ⇒ actorSystem(x.system)
      case x: ActorSystem  ⇒ x
      case _               ⇒ throw new IllegalStateException
    }

  private[http] implicit def enhanceByteArray(array: Array[Byte]): EnhancedByteArray = new EnhancedByteArray(array)
  private[http] implicit def enhanceConfig(config: Config): EnhancedConfig = new EnhancedConfig(config)
  private[http] implicit def enhanceString_(s: String): EnhancedString = new EnhancedString(s)
}

