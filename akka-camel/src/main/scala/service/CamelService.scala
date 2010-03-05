/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.camel.service

import java.io.InputStream

import org.apache.camel.builder.RouteBuilder

import se.scalablesolutions.akka.actor.{Actor, ActorRegistry}
import se.scalablesolutions.akka.annotation.consume
import se.scalablesolutions.akka.util.{Bootable, Logging}
import se.scalablesolutions.akka.camel.{CamelContextManager, Consumer}

/**
 * Started by the Kernel to expose certain actors as Camel endpoints. It uses
 * se.scalablesolutions.akka.camel.CamelContextManage to create and manage the
 * lifecycle of a global CamelContext. This class further uses the
 * se.scalablesolutions.akka.camel.service.CamelServiceRouteBuilder to implement
 * routes from Camel endpoints to actors.
 *
 * @see CamelRouteBuilder
 *
 * @author Martin Krasser
 */
trait CamelService extends Bootable with Logging {

  import CamelContextManager._

  abstract override def onLoad = {
    super.onLoad
    if (!initialized) init()
    context.addRoutes(new CamelServiceRouteBuilder)
    context.setStreamCaching(true)
    start()
  }

  abstract override def onUnload = {
    stop()
    super.onUnload
  }

}

/**
 * Implements routes from Camel endpoints to actors. It searches the registry for actors
 * that are either annotated with @se.scalablesolutions.akka.annotation.consume or mix in
 * se.scalablesolutions.akka.camel.Consumer and exposes them as Camel endpoints.
 *
 * @author Martin Krasser
 */
class CamelServiceRouteBuilder extends RouteBuilder with Logging {

  def configure = {
    val actors = ActorRegistry.actors

    // TODO: avoid redundant registrations
    actors.filter(isConsumeAnnotated _).foreach { actor: Actor =>
      val fromUri = actor.getClass.getAnnotation(classOf[consume]).value()
      configure(fromUri, "actor:id:%s" format actor.getId)
      log.debug("registered actor (id=%s) for consuming messages from %s "
          format (actor.getId, fromUri))
    }

    // TODO: avoid redundant registrations
    actors.filter(isConsumerInstance _).foreach { actor: Actor =>
      val fromUri = actor.asInstanceOf[Consumer].endpointUri
      configure(fromUri, "actor:uuid:%s" format actor.uuid)
      log.debug("registered actor (uuid=%s) for consuming messages from %s "
          format (actor.uuid, fromUri))
    }
  }

  private def configure(fromUri: String, toUri: String) {
    val schema = fromUri take fromUri.indexOf(":") // e.g. "http" from "http://whatever/..."
    bodyConversions.get(schema) match {
      case Some(clazz) => from(fromUri).convertBodyTo(clazz).to(toUri)
      case None        => from(fromUri).to(toUri)
    }
  }

  // TODO: make conversions configurable
  private def bodyConversions = Map(
    "file" -> classOf[InputStream]
  )

  private def isConsumeAnnotated(actor: Actor) =
    actor.getClass.getAnnotation(classOf[consume]) ne null

  private def isConsumerInstance(actor: Actor) =
    actor.isInstanceOf[Consumer]

}
