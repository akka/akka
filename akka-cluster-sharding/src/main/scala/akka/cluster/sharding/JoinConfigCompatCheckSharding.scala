/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import akka.annotation.InternalApi
import akka.cluster.{ ConfigValidation, JoinConfigCompatChecker }
import com.typesafe.config.Config
import scala.collection.{ immutable ⇒ im }

/**
 * INTERNAL API
 */
@InternalApi
final class JoinConfigCompatCheckSharding extends JoinConfigCompatChecker {

  override def requiredKeys = im.Seq("akka.cluster.sharding.state-store-mode")

  override def check(toCheck: Config, actualConfig: Config): ConfigValidation =
    JoinConfigCompatChecker.fullMatch(requiredKeys, toCheck, actualConfig)
}
