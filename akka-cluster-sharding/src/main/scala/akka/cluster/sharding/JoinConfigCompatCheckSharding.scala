/*
 * Copyright (C) 2009-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import scala.collection.{ immutable => im }

import com.typesafe.config.Config

import akka.annotation.InternalApi
import akka.cluster.{ ConfigValidation, JoinConfigCompatChecker }

/**
 * INTERNAL API
 */
@InternalApi
final class JoinConfigCompatCheckSharding extends JoinConfigCompatChecker {

  override def requiredKeys: im.Set[String] =
    im.Set("akka.cluster.sharding.state-store-mode")

  override def check(toCheck: Config, actualConfig: Config): ConfigValidation =
    JoinConfigCompatChecker.fullMatch(requiredKeys, toCheck, actualConfig)
}
