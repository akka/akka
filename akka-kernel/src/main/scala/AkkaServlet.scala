/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.rest

import config.ConfiguratorRepository
import util.Logging

import com.sun.jersey.api.core.ResourceConfig
import com.sun.jersey.spi.container.servlet.ServletContainer
import com.sun.jersey.spi.container.WebApplication

import javax.servlet.{ServletConfig}
import javax.servlet.http.{HttpServletRequest, HttpServletResponse}

import org.atmosphere.cpr.{AtmosphereServletProcessor, AtmosphereEvent}
import org.atmosphere.cpr.AtmosphereServlet.AtmosphereHandlerWrapper
import org.atmosphere.container.GrizzlyCometSupport
import org.atmosphere.handler.ReflectorServletProcessor
import org.atmosphere.core.{JerseyBroadcaster}

import java.net.URLClassLoader
import java.io.InputStream

import scala.collection.jcl.Conversions._

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class AkkaServlet extends ServletContainer with AtmosphereServletProcessor with Logging {

  override def initiate(rc: ResourceConfig, wa: WebApplication) = {
    akka.Kernel.boot // will boot if not already booted by 'main'
    val configurators = ConfiguratorRepository.getConfigurators

    rc.getClasses.addAll(configurators.flatMap(_.getComponentInterfaces))
    log.info("Starting AkkaServlet with ResourceFilters: " + rc.getProperty("com.sun.jersey.spi.container.ResourceFilters"));
    rc.getProperties.put("com.sun.jersey.spi.container.ResourceFilters", "org.atmosphere.core.AtmosphereFilter")
    //rc.getFeatures.put("com.sun.jersey.config.feature.Redirect", true)
    //rc.getFeatures.put("com.sun.jersey.config.feature.ImplicitViewables",true)

    wa.initiate(rc, new ActorComponentProviderFactory(configurators))
  }

  // Borrowed from AbstractReflectorAtmosphereHandler
  override def onMessage(event: AtmosphereEvent[HttpServletRequest, HttpServletResponse]): AtmosphereEvent[_, _] = {
    if (event.getMessage ne null) {
      val isUsingStream = try {
        event.getResponse.getWriter
        false
      } catch { case e: IllegalStateException => true }

      val data = event.getMessage.toString
      if (isUsingStream) {
        if (data != null) event.getResponse.getOutputStream.write(data.getBytes)
        event.getResponse.getOutputStream.flush
      } else {
        event.getResponse.getWriter.write(data)
        event.getResponse.getWriter.flush
      }
    } else log.info("Null event message: req[%s] res[%s]", event.getRequest, event.getResponse)
    event
  }

  override def onEvent(event: AtmosphereEvent[HttpServletRequest, HttpServletResponse]): AtmosphereEvent[_, _] = {
    event.getRequest.setAttribute(ReflectorServletProcessor.ATMOSPHERE_EVENT, event)
    event.getRequest.setAttribute(ReflectorServletProcessor.ATMOSPHERE_HANDLER, this)
    service(event.getRequest, event.getResponse)
    event
  }
}

class AkkaCometServlet extends org.atmosphere.cpr.AtmosphereServlet {
  override def init(sconf: ServletConfig) = {
    val servlet = new AkkaServlet
    config = new AtmosphereConfig { ah = servlet }
    atmosphereHandlers.put("", new AtmosphereHandlerWrapper(servlet, new JerseyBroadcaster))
    setCometSupport(new GrizzlyCometSupport(config))
    getCometSupport.init(sconf)
    servlet.init(sconf)
  }

  override def loadAtmosphereDotXml(is: InputStream, urlc: URLClassLoader) = () //Hide it
}
