/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import java.util.concurrent.atomic.AtomicReference
import akka.remote.testkit.MultiNodeConfig
import akka.remote.FailureDetector
import com.typesafe.config.Config

/**
 * User controllable "puppet" failure detector.
 */
class FailureDetectorPuppet(config: Config) extends FailureDetector {

  trait Status
  object Up extends Status
  object Down extends Status
  object Unknown extends Status

  private val status: AtomicReference[Status] = new AtomicReference(Unknown)

  def markNodeAsUnavailable(): Unit = status.set(Down)

  def markNodeAsAvailable(): Unit = status.set(Up)

  override def isAvailable: Boolean = status.get match {
    case Unknown | Up ⇒ true
    case Down         ⇒ false
  }

  override def isMonitoring: Boolean = status.get != Unknown

  override def heartbeat(): Unit = status.compareAndSet(Unknown, Up)

}

