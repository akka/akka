/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.tck

/**
 * Specifies timeouts for the TCK
 */
object Timeouts {

  def publisherShutdownTimeoutMillis: Int = 3000

  def defaultTimeoutMillis: Int = 800

}
