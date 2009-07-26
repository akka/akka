/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.jersey

import kernel.Kernel
import config.ConfiguratorRepository
import util.Logging

import com.sun.jersey.api.core.{DefaultResourceConfig, ResourceConfig}
import com.sun.jersey.spi.container.servlet.ServletContainer
import com.sun.jersey.spi.container.WebApplication

import javax.servlet.{ServletConfig}
import java.net.{URL,URLClassLoader}
import java.util.HashSet


class AkkaServlet extends ServletContainer{

  override def initiate(rc: ResourceConfig, wa: WebApplication) = {
    //  super.initiate(rc, wa)
      
    Kernel.boot // will boot if not already booted by 'main'
    val configurators = ConfiguratorRepository.getConfiguratorsFor(getServletContext);
    val set = new HashSet[Class[_]]
    for {
      conf <- configurators
      clazz <- conf.getComponentInterfaces
    } set.add(clazz)

    wa.initiate(
      new DefaultResourceConfig(set),
      new ActorComponentProviderFactory(configurators))
  }
}

class AkkaCometServlet extends org.atmosphere.cpr.AtmosphereServlet with Logging
  {
      override def init(sconf : ServletConfig) = {

        val url = sconf.getServletContext().getResource("/WEB-INF/classes/")
        val urlC = new URLClassLoader(Array(url),Thread.currentThread().getContextClassLoader())

        loadAtmosphereDotXml(sconf.getServletContext.getResourceAsStream("META-INF/atmosphere.xml"), urlC);

        super.init(sconf)
      }
  }
