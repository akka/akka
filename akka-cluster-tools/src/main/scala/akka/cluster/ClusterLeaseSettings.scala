/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster

import akka.annotation.ApiMayChange

import scala.concurrent.duration.FiniteDuration
import akka.util.JavaDurationConverters._
import akka.util.PrettyDuration._

@ApiMayChange
class ClusterLeaseSettings private[akka] (val leaseImplementation: String, val leaseRetryInterval: FiniteDuration) {
  def getLeaseRetryInterval(): java.time.Duration = leaseRetryInterval.asJava

  override def toString = s"ClusterLeaseSettings($leaseImplementation, ${leaseRetryInterval.pretty})"
}
