/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.internal

import akka.annotation.InternalApi
import akka.cluster.{ ConfigValidation, JoinConfigCompatChecker }
import com.typesafe.config.Config

import scala.collection.{ immutable ⇒ im }

/**
 * INTERNAL API
 */
@InternalApi
private[akka] final class JoinConfigCompatCheckClusterSharding extends JoinConfigCompatChecker {

  override def requiredKeys: im.Seq[String] =
    im.Seq("akka.cluster.sharding.number-of-shards")

  override def check(toCheck: Config, actualConfig: Config): ConfigValidation =
    JoinConfigCompatChecker.fullMatch(requiredKeys, toCheck, actualConfig)
}

