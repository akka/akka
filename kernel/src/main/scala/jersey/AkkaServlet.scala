/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.jersey

import com.sun.jersey.api.core.{DefaultResourceConfig, ResourceConfig}
import com.sun.jersey.spi.container.servlet.ServletContainer
import com.sun.jersey.spi.container.WebApplication
import config.ActiveObjectConfigurator
import java.util.{HashSet, ArrayList}
class AkkaServlet extends ServletContainer {

  override def initiate(rc: ResourceConfig, wa: WebApplication) = {
    val configurator = ActiveObjectConfigurator.getConfiguratorFor(getServletContext);
    val set = new HashSet[Class[_]]
    for (c <- configurator.getComponentInterfaces) {
      println("========== " + c)
      set.add(c)
    }

    wa.initiate(
      new DefaultResourceConfig(set),
      new ActiveObjectComponentProviderFactory(configurator));
  }
}