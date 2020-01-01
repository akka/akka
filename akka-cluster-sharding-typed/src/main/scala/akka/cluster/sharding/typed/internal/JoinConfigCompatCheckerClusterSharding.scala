/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.internal

import akka.annotation.InternalApi
import akka.cluster.{ ConfigValidation, JoinConfigCompatChecker, Valid }
import com.typesafe.config.Config

import scala.collection.{ immutable => im }

/**
 * INTERNAL API
 */
@InternalApi
private[akka] final class JoinConfigCompatCheckerClusterSharding extends JoinConfigCompatChecker {

  override def requiredKeys: im.Seq[String] =
    im.Seq("akka.cluster.sharding.number-of-shards")

  override def check(toCheck: Config, actualConfig: Config): ConfigValidation = {
    if (toCheck.hasPath(requiredKeys.head))
      JoinConfigCompatChecker.fullMatch(requiredKeys, toCheck, actualConfig)
    else
      Valid // support for rolling update, property doesn't exist in previous versions
  }
}
