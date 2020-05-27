/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster

import scala.collection.{ immutable => im }

import com.typesafe.config.Config

import akka.annotation.InternalApi

/**
 * INTERNAL API
 */
@InternalApi
final class JoinConfigCompatCheckCluster extends JoinConfigCompatChecker {

  override def requiredKeys = im.Seq("akka.cluster.downing-provider-class")

  override def check(toCheck: Config, actualConfig: Config): ConfigValidation =
    JoinConfigCompatChecker.fullMatch(requiredKeys, toCheck, actualConfig)
}
