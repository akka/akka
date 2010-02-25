/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.camel.service

import java.io.InputStream

import org.apache.camel.builder.RouteBuilder

import se.scalablesolutions.akka.actor.{Actor, ActorRegistry}
import se.scalablesolutions.akka.annotation.consume
import se.scalablesolutions.akka.camel.CamelConsumer
import se.scalablesolutions.akka.util.{Bootable, Logging}

/**
 * Started by the Kernel to expose actors as Camel endpoints.
 *
 * @see CamelRouteBuilder
 *
 * @author Martin Krasser
 */
trait CamelService extends Bootable with Logging {

  import CamelContextManager.context

  abstract override def onLoad = {
    super.onLoad
    context.addRoutes(new CamelRouteBuilder)
    context.setStreamCaching(true)
    context.start
    log.info("Camel context started")
  }

  abstract override def onUnload = {
    super.onUnload
    context.stop
    log.info("Camel context stopped")
  }

}

/**
 * Generic route builder that searches the registry for actors that are
 * either annotated with @se.scalablesolutions.akka.annotation.consume or
 * mixed in se.scalablesolutions.akka.camel.CamelConsumer and exposes them
 * as Camel endpoints.
 *
 * @author Martin Krasser
 */
class CamelRouteBuilder extends RouteBuilder with Logging {

  def configure = {
    val actors = ActorRegistry.actors

    //
    // TODO: resolve/clarify issues with ActorRegistry
    //       - custom Actor.id ignored
    //       - actor de-registration issues
    //       - multiple registration with same id/uuid possible
    //

    // TODO: avoid redundant registrations
    actors.filter(isConsumeAnnotated _).foreach { actor: Actor =>
      val fromUri = actor.getClass.getAnnotation(classOf[consume]).value()
      configure(fromUri, "actor://%s" format actor.id)
      log.debug("registered actor (id=%s) for consuming messages from %s "
          format (actor.id, fromUri))
    }

    // TODO: avoid redundant registrations
    actors.filter(isConsumerInstance _).foreach { actor: Actor =>
      val fromUri = actor.asInstanceOf[CamelConsumer].endpointUri
      configure(fromUri, "actor://%s/%s" format (actor.id, actor.uuid))
      log.debug("registered actor (id=%s, uuid=%s) for consuming messages from %s "
          format (actor.id, actor.uuid, fromUri))
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
    actor.isInstanceOf[CamelConsumer]

}
