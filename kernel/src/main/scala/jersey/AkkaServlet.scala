/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.jersey

import kernel.Kernel
import config.ConfiguratorRepository

import com.sun.jersey.api.core.{DefaultResourceConfig, ResourceConfig}
import com.sun.jersey.spi.container.servlet.ServletContainer
import com.sun.jersey.spi.container.WebApplication

import java.util.HashSet

class AkkaServlet extends ServletContainer {

  override def initiate(rc: ResourceConfig, wa: WebApplication) = {
    Kernel.boot // will boot if not already booted by 'main'
    val configurators = ConfiguratorRepository.getConfiguratorsFor(getServletContext);
    val set = new HashSet[Class[_]]
    for {
      conf <- configurators
      clazz <- conf.getComponentInterfaces
    } set.add(clazz)

    wa.initiate(
      new DefaultResourceConfig(set),
      new ActorComponentProviderFactory(configurators));
  }
}