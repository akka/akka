/*
 * Copyright (C) 2009-2016 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl

import akka.http.impl.settings.HostConnectionPoolSetup

trait HostConnectionPool {
  def setup: HostConnectionPoolSetup
}
