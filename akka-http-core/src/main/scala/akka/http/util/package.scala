/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import language.implicitConversions
import com.typesafe.config.Config
import akka.actor.{ ActorRefFactory, ActorContext, ExtendedActorSystem }

package object util {

  def actorSystem(implicit refFactory: ActorRefFactory): ExtendedActorSystem =
    refFactory match {
      case x: ActorContext        ⇒ actorSystem(x.system)
      case x: ExtendedActorSystem ⇒ x
      case x                      ⇒ throw new IllegalStateException
    }

  // implicits
  implicit def pimpByteArray(array: Array[Byte]): PimpedByteArray = new PimpedByteArray(array)
  implicit def pimpConfig(config: Config): PimpedConfig = new PimpedConfig(config)
  implicit def pimpString_(s: String): PimpedString = new PimpedString(s)
}

