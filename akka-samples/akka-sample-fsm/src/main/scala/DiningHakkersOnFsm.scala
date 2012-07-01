/**
 * Copyright (C) 2009-2010 Typesafe Inc. <http://www.typesafe.com>.
 */
package sample.fsm.dining.fsm

import akka.actor._
import akka.actor.FSM._
import akka.util.Duration
import akka.util.duration._

/*
* Some messages for the chopstick
*/
sealed trait ChopstickMessage
object Take extends ChopstickMessage
object Put extends ChopstickMessage
case class Taken(chopstick: ActorRef) extends ChopstickMessage
case class Busy(chopstick: ActorRef) extends ChopstickMessage

/**
 * Some states the chopstick can be in
 */
sealed trait ChopstickState
case object Available extends ChopstickState
case object Taken extends ChopstickState

/**
 * Some state container for the chopstick
 */
case class TakenBy(hakker: ActorRef)

/*
* A chopstick is an actor, it can be taken, and put back
*/
class Chopstick extends Actor with FSM[ChopstickState, TakenBy] {
  import context._

  // A chopstick begins its existence as available and taken by no one
  startWith(Available, TakenBy(system.deadLetters))

  // When a chopstick is available, it can be taken by a some hakker
  when(Available) {
    case Event(Take, _) ⇒
      goto(Taken) using TakenBy(sender) replying Taken(self)
  }

  // When a chopstick is taken by a hakker
  // It will refuse to be taken by other hakkers
  // But the owning hakker can put it back
  when(Taken) {
    case Event(Take, currentState) ⇒
      stay replying Busy(self)
    case Event(Put, TakenBy(hakker)) if sender == hakker ⇒
      goto(Available) using TakenBy(system.deadLetters)
  }

  // Initialze the chopstick
  initialize
}

/**
 * Some fsm hakker messages
 */
sealed trait FSMHakkerMessage
object Think extends FSMHakkerMessage

/**
 * Some fsm hakker states
 */
sealed trait FSMHakkerState
case object Waiting extends FSMHakkerState
case object Thinking extends FSMHakkerState
case object Hungry extends FSMHakkerState
case object WaitForOtherChopstick extends FSMHakkerState
case object FirstChopstickDenied extends FSMHakkerState
case object Eating extends FSMHakkerState

/**
 * Some state container to keep track of which chopsticks we have
 */
case class TakenChopsticks(left: Option[ActorRef], right: Option[ActorRef])

/*
* A fsm hakker is an awesome dude or dudette who either thinks about hacking or has to eat ;-)
*/
class FSMHakker(name: String, left: ActorRef, right: ActorRef) extends Actor with FSM[FSMHakkerState, TakenChopsticks] {

  //All hakkers start waiting
  startWith(Waiting, TakenChopsticks(None, None))

  when(Waiting) {
    case Event(Think, _) ⇒
      println("%s starts to think".format(name))
      startThinking(5 seconds)
  }

  //When a hakker is thinking it can become hungry
  //and try to pick up its chopsticks and eat
  when(Thinking) {
    case Event(StateTimeout, _) ⇒
      left ! Take
      right ! Take
      goto(Hungry)
  }

  // When a hakker is hungry it tries to pick up its chopsticks and eat
  // When it picks one up, it goes into wait for the other
  // If the hakkers first attempt at grabbing a chopstick fails,
  // it starts to wait for the response of the other grab
  when(Hungry) {
    case Event(Taken(`left`), _) ⇒
      goto(WaitForOtherChopstick) using TakenChopsticks(Some(left), None)
    case Event(Taken(`right`), _) ⇒
      goto(WaitForOtherChopstick) using TakenChopsticks(None, Some(right))
    case Event(Busy(_), _) ⇒
      goto(FirstChopstickDenied)
  }

  // When a hakker is waiting for the last chopstick it can either obtain it
  // and start eating, or the other chopstick was busy, and the hakker goes
  // back to think about how he should obtain his chopsticks :-)
  when(WaitForOtherChopstick) {
    case Event(Taken(`left`), TakenChopsticks(None, Some(right))) ⇒ startEating(left, right)
    case Event(Taken(`right`), TakenChopsticks(Some(left), None)) ⇒ startEating(left, right)
    case Event(Busy(chopstick), TakenChopsticks(leftOption, rightOption)) ⇒
      leftOption.foreach(_ ! Put)
      rightOption.foreach(_ ! Put)
      startThinking(10 milliseconds)
  }

  private def startEating(left: ActorRef, right: ActorRef): State = {
    println("%s has picked up %s and %s and starts to eat".format(name, left.path.name, right.path.name))
    goto(Eating) using TakenChopsticks(Some(left), Some(right)) forMax (5 seconds)
  }

  // When the results of the other grab comes back,
  // he needs to put it back if he got the other one.
  // Then go back and think and try to grab the chopsticks again
  when(FirstChopstickDenied) {
    case Event(Taken(secondChopstick), _) ⇒
      secondChopstick ! Put
      startThinking(10 milliseconds)
    case Event(Busy(chopstick), _) ⇒
      startThinking(10 milliseconds)
  }

  // When a hakker is eating, he can decide to start to think,
  // then he puts down his chopsticks and starts to think
  when(Eating) {
    case Event(StateTimeout, _) ⇒
      println("%s puts down his chopsticks and starts to think".format(name))
      left ! Put
      right ! Put
      startThinking(5 seconds)
  }

  // Initialize the hakker
  initialize

  private def startThinking(duration: Duration): State = {
    goto(Thinking) using TakenChopsticks(None, None) forMax duration
  }
}

/*
* Alright, here's our test-harness
*/
object DiningHakkersOnFsm {

  val system = ActorSystem()

  def main(args: Array[String]): Unit = run

  def run = {
    // Create 5 chopsticks
    val chopsticks = for (i ← 1 to 5) yield system.actorOf(Props[Chopstick], "Chopstick" + i)
    // Create 5 awesome fsm hakkers and assign them their left and right chopstick
    val hakkers = for {
      (name, i) ← List("Ghosh", "Boner", "Klang", "Krasser", "Manie").zipWithIndex
    } yield system.actorOf(Props(new FSMHakker(name, chopsticks(i), chopsticks((i + 1) % 5))))

    hakkers.foreach(_ ! Think)
  }
}
