/**
 * Copyright (C) 2016 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.settings

/** INTERNAL API */
final case class HostConnectionPoolSetup(host: String, port: Int, setup: ConnectionPoolSetup)

