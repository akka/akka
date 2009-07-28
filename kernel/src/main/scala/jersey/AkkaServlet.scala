/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.jersey

import kernel.Kernel
import config.ConfiguratorRepository
import util.Logging

import com.sun.jersey.api.core.{DefaultResourceConfig, ResourceConfig,ClasspathResourceConfig}
import com.sun.jersey.spi.container.servlet.ServletContainer
import com.sun.jersey.spi.container.WebApplication

import com.sun.jersey.server.impl.component.{IoCResourceFactory}

import javax.servlet.{ServletConfig}
import javax.servlet.http.{HttpServletRequest,HttpServletResponse}

import org.atmosphere.cpr.{AtmosphereServlet,AtmosphereServletProcessor,AtmosphereEvent,DefaultBroadcaster}
import org.atmosphere.cpr.AtmosphereServlet.{AtmosphereHandlerWrapper}
import org.atmosphere.container.GrizzlyCometSupport
import org.atmosphere.handler.ReflectorServletProcessor
import org.atmosphere.core.{JerseyBroadcaster}

import java.util.{HashSet, HashMap}
import java.net.{URL,URLClassLoader}
import java.io.{InputStream}

import scala.collection.jcl.Conversions._


class AkkaServlet extends ServletContainer with AtmosphereServletProcessor with Logging {

  override def initiate(rc: ResourceConfig, wa: WebApplication) = {

    Kernel.boot // will boot if not already booted by 'main'
    val configurators = ConfiguratorRepository.getConfiguratorsFor(getServletContext)
  
    rc.getClasses.addAll(configurators.flatMap(_.getComponentInterfaces))
    rc.getProperties.put("com.sun.jersey.spi.container.ResourceFilters","org.atmosphere.core.AtmosphereFilter")
    //rc.getFeatures.put("com.sun.jersey.config.feature.Redirect", true)
    //rc.getFeatures.put("com.sun.jersey.config.feature.ImplicitViewables",true)

    wa.initiate(rc,new ActorComponentProviderFactory(configurators))
  }

    override def onMessage(event : AtmosphereEvent[HttpServletRequest,HttpServletResponse]) : AtmosphereEvent[_,_] =
    {
        //log.info("onMessage: " + event.getMessage.toString)

        if(event.getMessage ne null)
        {
            var isUsingStream = false
            try {
                event.getResponse.getWriter
            } catch {
                case e: IllegalStateException => isUsingStream = true
            }

            if (isUsingStream){
                event.getResponse.getOutputStream.write(event.getMessage.toString.getBytes);
                event.getResponse.getOutputStream.flush
            } else {
                event.getResponse.getWriter.write(event.getMessage.toString)
                event.getResponse.getWriter.flush
            }
        }
        else
            log.info(new NullPointerException, "Null event message :/")

        event
    }

    override def onEvent(event : AtmosphereEvent[HttpServletRequest,HttpServletResponse]) : AtmosphereEvent[_,_] =
    {
        //log.info("onEvent: " + event.getMessage)
        event.getRequest.setAttribute(ReflectorServletProcessor.ATMOSPHERE_EVENT, event)
        event.getRequest.setAttribute(ReflectorServletProcessor.ATMOSPHERE_HANDLER, this)

        service(event.getRequest, event.getResponse)

        event
    }
}

class AkkaCometServlet extends org.atmosphere.cpr.AtmosphereServlet with Logging
{
      override def init(sconf : ServletConfig) = {
        val servlet = new AkkaServlet

        atmosphereHandlers.put("", new AtmosphereHandlerWrapper(servlet,new JerseyBroadcaster()))

        setCometSupport(new GrizzlyCometSupport(new AtmosphereConfig{ ah = servlet }))
        getCometSupport.init(sconf)

        servlet.init(sconf)
      }

      override def loadAtmosphereDotXml(is : InputStream, urlc :URLClassLoader) = () //Hide it
}
