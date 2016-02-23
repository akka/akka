/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.settings

/** INTERNAL API */
final case class HostConnectionPoolSetup(host: String, port: Int, setup: ConnectionPoolSetup)

