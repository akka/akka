/*
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package com.lightbend.sslconfig.akka

import javax.net.ssl.{ SSLContext, SSLEngine }

/**
 * Gives the chance to configure the SSLContext before it is going to be used.
 * The passed in context will be already set in client mode and provided with hostInfo during initialization.
 */
trait SSLEngineConfigurator {
  def configure(engine: SSLEngine, sslContext: SSLContext): SSLEngine
}
