/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import akka.AkkaException
import com.mongodb.async._
import org.bson.util._
import org.bson.io.OutputBuffer
import org.bson.types.ObjectId
import java.io.InputStream
import org.bson.collection._
import akka.actor.{ ActorRef, ActorSystem }
import akka.dispatch.Envelope

/**
 * A container message for durable mailbox messages, which can be easily stuffed into
 * and out of MongoDB.
 *
 * Does not use the Protobuf protocol, instead using a pure Mongo based serialization for sanity
 * (and mongo-iness).
 *
 * This should eventually branch out into a more flat, compound solution for all remote actor stuff
 * TODO - Integrate Salat or a Salat-Based solution for the case classiness
 *
 * @author <a href="http://evilmonkeylabs.com">Brendan W. McAdams</a>
 */
case class MongoDurableMessage(
  val ownerPath: String,
  val message: Any,
  val sender: ActorRef,
  val _id: ObjectId = new ObjectId) {

  def envelope(system: ActorSystem) = Envelope(message, sender)(system)
}

// vim: set ts=2 sw=2 sts=2 et:
