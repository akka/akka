/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.typed.internal.receptionist

import akka.annotation.InternalApi
import akka.cluster.{ ConfigValidation, JoinConfigCompatChecker }
import com.typesafe.config.Config

/**
 * INTERNAL API
 *
 * Verifies that receptionist distributed-key-count are the same across cluster nodes
 */
@InternalApi
final class ClusterReceptionistConfigCompatChecker extends JoinConfigCompatChecker {

  override def requiredKeys = "akka.cluster.typed.receptionist.distributed-key-count" :: Nil

  override def check(toCheck: Config, actualConfig: Config): ConfigValidation =
    JoinConfigCompatChecker.fullMatch(requiredKeys, toCheck, actualConfig)
}

