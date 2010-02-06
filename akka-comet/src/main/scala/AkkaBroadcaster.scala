/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.comet

import org.atmosphere.cpr.{AtmosphereResourceEvent, AtmosphereResource}
import java.io.IOException
import com.sun.jersey.spi.container.ContainerResponse
import org.atmosphere.jersey.AtmosphereFilter._
import javax.servlet.http.HttpServletRequest
import se.scalablesolutions.akka.actor.Actor
import javax.ws.rs.core.Response
import se.scalablesolutions.akka.util.Logging

case class AkkaBroadcast(resource :  AtmosphereResource[_,_],event : AtmosphereResourceEvent[_,_])

class AkkaBroadcaster extends org.atmosphere.util.SimpleBroadcaster with Logging {
  name = classOf[AkkaBroadcaster].getName
  
  val actor = new Actor {
                    def receive = {
                      case AkkaBroadcast(r,e) => try {

                        log.info("broadcast(r,e) processed")
                        
                        val httpReq = r.asInstanceOf[AtmosphereResource[HttpServletRequest,_]].getRequest
                        val cr = httpReq.getAttribute(CONTAINER_RESPONSE).asInstanceOf[ContainerResponse]

                        e.getMessage match {
                          case res : Response => {
                            log.info("Writing response")
                            cr.setResponse(res)
                            cr.write()
                          }

                          case any : AnyRef => {
                            log.info("Writing message")
                            cr.reset()
                            cr.setEntity(any)
                            cr.write()
                            cr.getOutputStream().flush()
                          }
                        }

                        if(java.lang.Boolean.TRUE == httpReq.getAttribute(RESUME_ON_BROADCAST))
                          r.resume()

                        log.info("broadcast(r,e) done")
                      } catch {
                        case ioe : IOException           => log.error(ioe,"Failed broadcast"); onException(ioe,r)
                        case ise : IllegalStateException => log.error(ise,"Failed broadcast"); onException(ise,r)
                        case e   : Exception             => log.error(e,"Failed broadcast");
                      }
                    }
              }

  override def start {
    actor.start
  }

  override def destroy {
    super.destroy
    actor.stop
  }

  start

  protected override def broadcast(r :  AtmosphereResource[_,_],
                                   e : AtmosphereResourceEvent[_,_]) = {
    log.info("broadcast(r,e) called")
    actor send AkkaBroadcast(r,e)
  }
}