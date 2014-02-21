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

case object SubscribeToHakkerStateChanges extends DiningHakkerMessage

case class HakkerStateChange(hakkerName: String, from: String, to: String)

case class TrackHakker(hakker: ActorRef) extends DiningHakkerMessage

case class GetEatingCount(hakkerName: String) extends DiningHakkerMessage

case class EatingCount(hakkerName: String, count: Int) extends DiningHakkerMessage
