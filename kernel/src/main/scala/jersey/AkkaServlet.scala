/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.jersey

import com.sun.jersey.api.core.ResourceConfig
import com.sun.jersey.spi.container.servlet.ServletContainer
import com.sun.jersey.spi.container.WebApplication

import config.ActiveObjectConfigurator

class AkkaServlet extends ServletContainer {

  override def initiate(rc: ResourceConfig, wa: WebApplication) = {
    val configurator = ActiveObjectConfigurator.getConfiguratorFor(getServletContext)
    wa.initiate(rc, new ActiveObjectComponentProviderFactory(configurator));
  }
}