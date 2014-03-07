package akka.sample.osgi.api

import akka.actor.ActorRef

/*
 * Define our messages, they basically speak for themselves
 */
sealed trait DiningHakkerMessage extends Serializable

final case class Busy(chopstick: ActorRef) extends DiningHakkerMessage

final case class Put(hakker: ActorRef) extends DiningHakkerMessage

final case class Take(hakker: ActorRef) extends DiningHakkerMessage

final case class Taken(chopstick: ActorRef) extends DiningHakkerMessage

case object Eat extends DiningHakkerMessage

case object Think extends DiningHakkerMessage

case object Identify extends DiningHakkerMessage

final case class Identification(name: String, busyWith: String) extends DiningHakkerMessage

case object SubscribeToHakkerStateChanges extends DiningHakkerMessage

final case class HakkerStateChange(hakkerName: String, from: String, to: String)

final case class TrackHakker(hakker: ActorRef) extends DiningHakkerMessage

final case class GetEatingCount(hakkerName: String) extends DiningHakkerMessage

final case class EatingCount(hakkerName: String, count: Int) extends DiningHakkerMessage
