package dining.hakkerz

import actor.Fsm
import se.scalablesolutions.akka.actor.{ActorRef, Actor}
import Actor._

/*
 * Some messages for the chopstick
 */
sealed trait ChopstickMessage
object Take extends ChopstickMessage
object Put extends ChopstickMessage
case class Taken(chopstick: ActorRef) extends ChopstickMessage
case class Busy(chopstick: ActorRef) extends ChopstickMessage

/**
 * Some state container for the chopstick
 */
case class TakenBy(hakker: Option[ActorRef])

/*
 * A chopstick is an actor, it can be taken, and put back
 */
class Chopstick(name: String) extends Actor with Fsm[TakenBy] {
  self.id = name

  // A chopstick begins its existence as available and taken by no one
  def initialState = State(NextState, available, TakenBy(None))

  // When a chopstick is available, it can be taken by a some hakker
  def available: StateFunction = {
    case Event(Take, _) =>
      State(Reply, taken, TakenBy(self.sender), replyValue = Some(Taken(self)))
  }

  // When a chopstick is taken by a hakker
  // It will refuse to be taken by other hakkers
  // But the owning hakker can put it back
  def taken: StateFunction = {
    case Event(Take, currentState) =>
      State(Reply, taken, currentState, replyValue = Some(Busy(self)))
    case Event(Put, TakenBy(hakker)) if self.sender == hakker =>
      State(NextState, available, TakenBy(None))
  }
}

/**
 * Some fsm hakker messages
 */
sealed trait FsmHakkerMessage
object Think extends FsmHakkerMessage

/**
 * Some state container to keep track of which chopsticks we have
 */
case class TakenChopsticks(left: Option[ActorRef], right: Option[ActorRef])

/*
 * A fsm hakker is an awesome dude or dudette who either thinks about hacking or has to eat ;-)
 */
class FsmHakker(name: String, left: ActorRef, right: ActorRef) extends Actor with Fsm[TakenChopsticks] {
  self.id = name

  //All hakkers start waiting
  def initialState = State(NextState, waiting, TakenChopsticks(None, None))

  def waiting: StateFunction = {
    case Event(Think, _) =>
      log.info("%s starts to think", name)
      startThinking(5000)
  }

  //When a hakker is thinking it can become hungry
  //and try to pick up its chopsticks and eat
  def thinking: StateFunction = {
    case Event(StateTimeout, current) =>
      left ! Take
      right ! Take
      State(NextState, hungry, current)
  }

  // When a hakker is hungry it tries to pick up its chopsticks and eat
  // When it picks one up, it goes into wait for the other
  // If the hakkers first attempt at grabbing a chopstick fails,
  // it starts to wait for the response of the other grab
  def hungry: StateFunction = {
    case Event(Taken(`left`), _) =>
      State(NextState, waitForOtherChopstick, TakenChopsticks(Some(left), None))
    case Event(Taken(`right`), _) =>
      State(NextState, waitForOtherChopstick, TakenChopsticks(None, Some(right)))
    case Event(Busy(_), current) =>
      State(NextState, firstChopstickDenied, current)
  }

  // When a hakker is waiting for the last chopstick it can either obtain it
  // and start eating, or the other chopstick was busy, and the hakker goes
  // back to think about how he should obtain his chopsticks :-)
  def waitForOtherChopstick: StateFunction = {
    case Event(Taken(`left`), TakenChopsticks(None, Some(right))) => startEating(left, right)
    case Event(Taken(`right`), TakenChopsticks(Some(left), None)) => startEating(left, right)
    case Event(Busy(chopstick), TakenChopsticks(leftOption, rightOption)) =>
      leftOption.foreach(_ ! Put)
      rightOption.foreach(_ ! Put)
      startThinking(10)
  }

  private def startEating(left: ActorRef, right: ActorRef): State = {
    log.info("%s has picked up %s and %s, and starts to eat", name, left.id, right.id)
    State(NextState, eating, TakenChopsticks(Some(left), Some(right)), timeout = Some(5000))
  }

  // When the results of the other grab comes back,
  // he needs to put it back if he got the other one.
  // Then go back and think and try to grab the chopsticks again
  def firstChopstickDenied: StateFunction = {
    case Event(Taken(secondChopstick), _) =>
      secondChopstick ! Put
      startThinking(10)
    case Event(Busy(chopstick), _) =>
      startThinking(10)
  }

  // When a hakker is eating, he can decide to start to think,
  // then he puts down his chopsticks and starts to think
  def eating: StateFunction = {
    case Event(StateTimeout, _) =>
      log.info("%s puts down his chopsticks and starts to think", name)
      left ! Put
      right ! Put
      startThinking(5000)
  }

  private def startThinking(period: Int): State = {
    State(NextState, thinking, TakenChopsticks(None, None), timeout = Some(period))
  }
}

/*
 * Alright, here's our test-harness
 */
object DiningHakkersOnFsm {
  def run {
    // Create 5 chopsticks
    val chopsticks = for (i <- 1 to 5) yield actorOf(new Chopstick("Chopstick " + i)).start
    // Create 5 awesome fsm hakkers and assign them their left and right chopstick
    val hakkers = for{
      (name, i) <- List("Ghosh", "Bonér", "Klang", "Krasser", "Manie").zipWithIndex
    } yield actorOf(new FsmHakker(name, chopsticks(i), chopsticks((i + 1) % 5))).start

    hakkers.foreach(_ ! Think)
  }
}