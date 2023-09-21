/*
 * Copyright (C) 2019-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.coordination.lease

import scala.concurrent.duration.FiniteDuration

import akka.util.JavaDurationConverters._
import akka.util.PrettyDuration._

/**
 * Note that if you define a custom lease name and have several Cluster Singletons or Cluster Sharding
 * entity types each one must have a unique lease name. If the lease name is undefined it will be derived
 * from ActorSystem name and other component names, but that may result in too long lease names.
 */
final class LeaseUsageSettings private[akka] (
    val leaseImplementation: String,
    val leaseRetryInterval: FiniteDuration,
    val leaseName: String) {
  def this(leaseImplementation: String, leaseRetryInterval: FiniteDuration) =
    this(leaseImplementation, leaseRetryInterval, leaseName = "")

  def getLeaseRetryInterval(): java.time.Duration = leaseRetryInterval.asJava

  override def toString = s"LeaseUsageSettings($leaseImplementation, ${leaseRetryInterval.pretty})"
}
