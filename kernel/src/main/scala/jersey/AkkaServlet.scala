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
import javax.servlet.http.{HttpServletRequest,HttpServletResponse}

import org.atmosphere.cpr.{AtmosphereServlet,AtmosphereServletProcessor,AtmosphereEvent}
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
    //  super.initiate(rc, wa)
      log.info("Initializing akka servlet")
    Kernel.boot // will boot if not already booted by 'main'
    val configurators = ConfiguratorRepository.getConfiguratorsFor(getServletContext)
    val set = new HashSet[Class[_]]
    for {
      conf <- configurators
      clazz <- conf.getComponentInterfaces
    } set.add(clazz)

    wa.initiate(
      new DefaultResourceConfig(set),
      new ActorComponentProviderFactory(configurators))
  }

    override def onMessage(event : AtmosphereEvent[HttpServletRequest,HttpServletResponse]) : AtmosphereEvent[_,_] =
    {
        log.info("AkkaServlet:onMessage")
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

        event
    }

    override def onEvent(event : AtmosphereEvent[HttpServletRequest,HttpServletResponse]) : AtmosphereEvent[_,_] =
    {
        event.getRequest.setAttribute(classOf[org.atmosphere.cpr.AtmosphereEvent[_,_]].getName, event)
        event.getRequest.setAttribute(classOf[AkkaServlet].getName, this)
        log.info("AkkaServlet:onEvent:beforeService")
        service(event.getRequest, event.getResponse)
        log.info("AkkaServlet:onEvent:afterService")
        event
    }
}

class AkkaCometServlet extends org.atmosphere.cpr.AtmosphereServlet with Logging
  {
      override def init(sconf : ServletConfig) = {

        log.info("initializing Akka comet servlet")
        val cfg = new AtmosphereConfig

        atmosphereHandlers = new java.util.HashMap[String, AtmosphereHandlerWrapper] {
            override def get(s : Object) : AtmosphereHandlerWrapper = {
                log.info("get handler for: " + s)
                
                super.get("")
            }
        }

        atmosphereHandlers.put("", new AtmosphereHandlerWrapper(new AkkaServlet,new JerseyBroadcaster()))

        super.setCometSupport(new GrizzlyCometSupport(cfg))
        getCometSupport.init(sconf)

        for(e <- atmosphereHandlers.entrySet){
            val h = e.getValue.atmosphereHandler
            if(h.isInstanceOf[AtmosphereServletProcessor])
                    (h.asInstanceOf[AtmosphereServletProcessor]).init(sconf)
        }

      }

      override def loadAtmosphereDotXml(is : InputStream, urlc :URLClassLoader)
      {
          log.info("hiding Atmosphere.xml")
      }
  }
