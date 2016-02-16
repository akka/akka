/*
 * Copyright (C) 2009-2016 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl

import java.util.concurrent.CompletionStage

import akka.Done
import akka.http.impl.settings.HostConnectionPoolSetup

import scala.concurrent.ExecutionContextExecutor

abstract class HostConnectionPool private[http] {
  def setup: HostConnectionPoolSetup

  /**
   * Asynchronously triggers the shutdown of the host connection pool.
   *
   * The produced [[CompletionStage]] is fulfilled when the shutdown has been completed.
   */
  def shutdown(ec: ExecutionContextExecutor): CompletionStage[Done]

}
