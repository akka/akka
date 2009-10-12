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

import org.atmosphere.cpr.{AtmosphereServletProcessor, AtmosphereResource, AtmosphereResourceEvent}
import org.atmosphere.cpr.AtmosphereServlet.AtmosphereHandlerWrapper
import org.atmosphere.container.GrizzlyCometSupport
import org.atmosphere.handler.{ReflectorServletProcessor,AbstractReflectorAtmosphereHandler}
import org.atmosphere.core.{JerseyBroadcaster}

import java.net.URLClassLoader
import java.io.InputStream

import scala.collection.jcl.Conversions._

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class AkkaServlet extends ServletContainer  with Logging {

    override def initiate(rc: ResourceConfig, wa: WebApplication) = {
        akka.Kernel.boot // will boot if not already booted by 'main'
        val configurators = ConfiguratorRepository.getConfigurators

        rc.getClasses.addAll(configurators.flatMap(_.getComponentInterfaces))
        rc.getProperties.put("com.sun.jersey.spi.container.ResourceFilters", akka.Config.config.getString("akka.rest.filters").getOrElse(""))

        wa.initiate(rc, new ActorComponentProviderFactory(configurators))
    }
}

class AkkaCometServlet extends org.atmosphere.cpr.AtmosphereServlet {

    override def loadConfiguration(sc : ServletConfig) : Unit = {
        val servlet = new AkkaServlet with AtmosphereServletProcessor {

            //Delegate to implement the behavior for AtmosphereHandler
            private val handler = new AbstractReflectorAtmosphereHandler {
                override def onRequest(event: AtmosphereResource[HttpServletRequest, HttpServletResponse]) : Unit = {
                    if(event ne null)
                    {
                        event.getRequest.setAttribute(ReflectorServletProcessor.ATMOSPHERE_RESOURCE, event)
                        event.getRequest.setAttribute(ReflectorServletProcessor.ATMOSPHERE_HANDLER, this)
                        service(event.getRequest, event.getResponse)
                    }
                }
            }

            override def onStateChange(event : AtmosphereResourceEvent[HttpServletRequest, HttpServletResponse] ) {
                if(event ne null)
                   handler onStateChange event
            }

            override def onRequest(resource: AtmosphereResource[HttpServletRequest, HttpServletResponse]) {
                if(resource ne null)
                   handler onRequest resource
            }
        }

        atmosphereHandlers.put("/*", new AtmosphereHandlerWrapper(servlet, new JerseyBroadcaster))
    }

    override def loadAtmosphereDotXml(is: InputStream, urlc: URLClassLoader) = () //Hide it
}
