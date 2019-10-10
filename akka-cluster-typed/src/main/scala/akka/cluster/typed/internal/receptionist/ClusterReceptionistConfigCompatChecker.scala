/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.typed.internal.receptionist

import akka.annotation.InternalApi
import akka.cluster.{ ConfigValidation, JoinConfigCompatChecker, Valid }
import com.typesafe.config.Config

/**
 * INTERNAL API
 *
 * Verifies that receptionist distributed-key-count are the same across cluster nodes
 */
@InternalApi
private[akka] final class ClusterReceptionistConfigCompatChecker extends JoinConfigCompatChecker {

  override def requiredKeys = "akka.cluster.typed.receptionist.distributed-key-count" :: Nil

  override def check(toCheck: Config, actualConfig: Config): ConfigValidation =
    if (toCheck.hasPath(requiredKeys.head))
      JoinConfigCompatChecker.fullMatch(requiredKeys, toCheck, actualConfig)
    else
      Valid // support for rolling update, property doesn't exist in previous versions
}
