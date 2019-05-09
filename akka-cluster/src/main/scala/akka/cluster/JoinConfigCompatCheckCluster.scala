/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster

import akka.annotation.InternalApi
import com.typesafe.config.Config

import scala.collection.{ immutable => im }

/**
 * INTERNAL API
 */
@InternalApi
final class JoinConfigCompatCheckCluster extends JoinConfigCompatChecker {

  override def requiredKeys = im.Seq("akka.cluster.downing-provider-class")

  override def check(toCheck: Config, actualConfig: Config): ConfigValidation =
    JoinConfigCompatChecker.fullMatch(requiredKeys, toCheck, actualConfig)
}
