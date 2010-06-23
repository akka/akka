/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.comet

import se.scalablesolutions.akka.util.Logging

import java.util.{List => JList}
import javax.servlet.{ServletConfig,ServletContext}
import javax.servlet.http.{HttpServletRequest, HttpServletResponse}
import com.sun.jersey.spi.container.servlet.ServletContainer

import org.atmosphere.container.GrizzlyCometSupport
import org.atmosphere.cpr.{AtmosphereServlet, AtmosphereServletProcessor, AtmosphereResource, AtmosphereResourceEvent,CometSupport,CometSupportResolver,DefaultCometSupportResolver}
import org.atmosphere.handler.{ReflectorServletProcessor, AbstractReflectorAtmosphereHandler}

class AtmosphereRestServlet extends ServletContainer with AtmosphereServletProcessor {
    //Delegate to implement the behavior for AtmosphereHandler
    private val handler = new AbstractReflectorAtmosphereHandler {
      override def onRequest(event: AtmosphereResource[HttpServletRequest, HttpServletResponse]) {
        if (event ne null) {
          event.getRequest.setAttribute(AtmosphereServlet.ATMOSPHERE_RESOURCE, event)
          event.getRequest.setAttribute(AtmosphereServlet.ATMOSPHERE_HANDLER, this)
          service(event.getRequest, event.getResponse)
        }
      }
    }

    override def onStateChange(event: AtmosphereResourceEvent[HttpServletRequest, HttpServletResponse]) {
      if (event ne null) handler onStateChange event
    }

    override def onRequest(resource: AtmosphereResource[HttpServletRequest, HttpServletResponse]) {
      handler onRequest resource
    }
  }

/**
 * Akka's Comet servlet to be used when deploying actors exposed as Comet (and REST) services in a
 * standard servlet container, e.g. not using the Akka Kernel.
 * <p/>
 * Used by the Akka Kernel to bootstrap REST and Comet.
 */
class AkkaServlet extends AtmosphereServlet with Logging {
  import se.scalablesolutions.akka.config.Config.{config => c}

  addInitParameter(AtmosphereServlet.DISABLE_ONSTATE_EVENT,"true")
  addInitParameter(AtmosphereServlet.BROADCASTER_CLASS,classOf[AkkaBroadcaster].getName)
  addInitParameter("com.sun.jersey.config.property.packages",c.getList("akka.rest.resource_packages").mkString(";"))
  addInitParameter("com.sun.jersey.spi.container.ResourceFilters",c.getList("akka.rest.filters").mkString(","))

  val servlet = new AtmosphereRestServlet {
    override def getInitParameter(key : String) = AkkaServlet.this.getInitParameter(key)
    override def getInitParameterNames() = AkkaServlet.this.getInitParameterNames()
  }

  override def getInitParameter(key : String) = Option(super.getInitParameter(key)).getOrElse(initParams.get(key))

  override def getInitParameterNames() = {
    val names = new java.util.Vector[String]()

    val i = initParams.keySet.iterator
    while(i.hasNext) names.add(i.next.toString)

    val e = super.getInitParameterNames
    while(e.hasMoreElements) names.add(e.nextElement.toString)

    names.elements
  }

  /**
   * We override this to avoid Atmosphere looking for it's atmosphere.xml file
   * Instead we specify what semantics we want in code.
   */
  override def loadConfiguration(sc: ServletConfig) {
    config.setSupportSession(false)
    isBroadcasterSpecified = true
    addAtmosphereHandler("/*", servlet, new AkkaBroadcaster)
  }

   /**
    * This method is overridden because Akka Kernel is bundles with Grizzly, so if we deploy the Kernel in another container,
    * we need to handle that.
    */
   override def createCometSupportResolver() : CometSupportResolver = {
      import scala.collection.JavaConversions._

      new DefaultCometSupportResolver(config) {
         type CS = CometSupport[_ <: AtmosphereResource[_,_]]
         override def resolveMultipleNativeSupportConflict(available : JList[Class[_ <: CS]]) : CS = {
             available.filter(_ != classOf[GrizzlyCometSupport]).toList match {
                 case Nil => new GrizzlyCometSupport(config)
                 case x :: Nil => newCometSupport(x.asInstanceOf[Class[_ <: CS]])
                 case _ => super.resolveMultipleNativeSupportConflict(available)
             }
        }

        override def resolve(useNativeIfPossible : Boolean, useBlockingAsDefault : Boolean) : CS = {
           val predef = config.getInitParameter("cometSupport")
           if (testClassExists(predef)) newCometSupport(predef)
           else super.resolve(useNativeIfPossible, useBlockingAsDefault)
        }
      }
  }
}
