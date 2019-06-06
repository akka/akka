/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.coordination.lease.javadsl

import java.util.Optional
import java.util.concurrent.CompletionStage

import akka.coordination.lease.LeaseSettings

abstract class Lease() {

  def getSettings(): LeaseSettings

  /**
   * Try to acquire the lease. The returned `CompletionStage` will be completed with `true`
   * if the lease could be acquired, i.e. no other owner is holding the lease.
   *
   * The returned `Future` will be completed with `false` if the lease for certain couldn't be
   * acquired, e.g. because some other owner is holding it. It's completed with [[akka.coordination.lease.LeaseException]]
   * failure if it might not have been able to acquire the lease, e.g. communication timeout
   * with the lease resource.
   *
   * The lease will be held by the [[LeaseSettings.ownerName]] until it is released
   * with [[Lease.release]]. A Lease implementation will typically also loose the ownership
   * if it can't maintain its authority, e.g. if it crashes or is partitioned from the lease
   * resource for too long.
   *
   * [[Lease.checkLease]] can be used to verify that the owner still has the lease.
   */
  def acquire(): CompletionStage[java.lang.Boolean]

  /**
   * Same as acquire with an additional callback
   * that is called if the lease is lost. The lease can be lose due to being unable
   * to communicate with the lease provider.
   * Implementations should not call leaseLostCallback until after the returned future
   * has been completed
   */
  def acquire(leaseLostCallback: java.util.function.Consumer[Optional[Throwable]]): CompletionStage[java.lang.Boolean]

  /**
   * Release the lease so some other owner can acquire it.
   */
  def release(): CompletionStage[java.lang.Boolean]

  /**
   * Check if the owner still holds the lease.
   * `true` means that it certainly holds the lease.
   * `false` means that it might not hold the lease, but it could, and for more certain
   * response you would have to use [[Lease#acquire()*]] or [[Lease#release]].
   */
  def checkLease(): Boolean

}
