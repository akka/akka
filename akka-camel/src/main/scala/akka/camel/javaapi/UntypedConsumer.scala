/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel.javaapi

import akka.actor.UntypedActor
import akka.camel._

/**
 *  Java-friendly Consumer.
 *
 * @see UntypedConsumerActor
 * @see RemoteUntypedConsumerActor
 *
 * @author Martin Krasser
 */
trait UntypedConsumer extends Consumer { self: UntypedActor ⇒
  final def endpointUri = getEndpointUri

  /**
   * Returns the Camel endpoint URI to consume messages from.
   */
  def getEndpointUri(): String

  def rich(message: Message): RichMessage = message
}

/**
 * Subclass this abstract class to create an MDB-style untyped consumer actor. This
 * class is meant to be used from Java.
 */
abstract class UntypedConsumerActor extends UntypedActor with UntypedConsumer
