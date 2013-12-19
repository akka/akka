/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>.
 */
package akka.sample.osgi.api

import akka.actor.ActorRef

/*
 * Define our messages, they basically speak for themselves
 */
sealed trait DiningHakkerMessage extends Serializable

case class Busy(chopstick: ActorRef) extends DiningHakkerMessage

case class Put(hakker: ActorRef) extends DiningHakkerMessage

case class Take(hakker: ActorRef) extends DiningHakkerMessage

case class Taken(chopstick: ActorRef) extends DiningHakkerMessage

case object Eat extends DiningHakkerMessage

case object Think extends DiningHakkerMessage

case object Identify extends DiningHakkerMessage

case class Identification(name: String, busyWith: String) extends DiningHakkerMessage
