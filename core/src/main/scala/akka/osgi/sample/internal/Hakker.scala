/**
 * Copyright (C) 2009-2010 Typesafe Inc. <http://www.typesafe.com>.
 */
package akka.osgi.sample.internal

import language.postfixOps
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.{CurrentClusterState, LeaderChanged}
import akka.event.Logging

//Akka adaptation of
//http://www.dalnefre.com/wp/2010/08/dining-philosophers-in-humus/

import akka.actor._
import scala.concurrent.duration._

import akka.osgi.sample.api._

/*
* A Chopstick is an actor, it can be taken, and put back
*/
class Chopstick extends Actor {

  val log = Logging(context.system, this)

  import context._

  //When a Chopstick is taken by a hakker
  //It will refuse to be taken by other hakkers
  //But the owning hakker can put it back
  def takenBy(hakker: ActorRef): Receive = {
    case Take(otherHakker) ⇒
      otherHakker ! Busy(self)
    case Put(`hakker`) ⇒
      become(available)
  }

  //When a Chopstick is available, it can be taken by a hakker
  def available: Receive = {
    case Take(hakker) ⇒
      log.info(self.path +" is taken by "+hakker)
      become(takenBy(hakker))
      hakker ! Taken(self)
  }

  //A Chopstick begins its existence as available
  def receive = available
}

/*
* A hakker is an awesome dude or dudett who either thinks about hacking or has to eat ;-)
*/
class Hakker(name: String, chair: Int) extends Actor {

  val log = Logging(context.system, this)

  log.info("Created Hakker at" +self.path)

  import context._

  val cluster = Cluster(context.system)

  override def preStart(){
    log.info(s"Hakker ($name) takes position($chair)")
    cluster.subscribe(self, classOf[LeaderChanged])
  }

  override def postStop(){
    log.info(s"Hakker ($name) leaves position($chair)")
    cluster.unsubscribe(self)
  }

  //When a hakker is thinking it can become hungry
  //and try to pick up its chopsticks and eat
  def thinking(left: ActorRef, right: ActorRef): Receive = {
    case Eat ⇒
      become(hungry(left, right) orElse (clusterEvents))
      left ! Take(self)
      right ! Take(self)
    case Identify ⇒ identify("Thinking")
  }

  //When a hakker is hungry it tries to pick up its chopsticks and eat
  //When it picks one up, it goes into wait for the other
  //If the hakkers first attempt at grabbing a chopstick fails,
  //it starts to wait for the response of the other grab
  def hungry(left: ActorRef, right: ActorRef): Receive = {
    case Taken(`left`) ⇒
      become(waiting_for(left, right, false) orElse (clusterEvents))
    case Taken(`right`) ⇒
      become(waiting_for(left, right, true) orElse (clusterEvents))
    case Busy(chopstick) ⇒
      become(denied_a_chopstick(left, right) orElse (clusterEvents))
    case Identify ⇒ identify("Hungry")
  }

  //When a hakker is waiting for the last chopstick it can either obtain it
  //and start eating, or the other chopstick was busy, and the hakker goes
  //back to think about how he should obtain his chopsticks :-)
  def waiting_for(left: ActorRef, right: ActorRef, waitingForLeft: Boolean): Receive = {
    case Taken(`left`) if waitingForLeft ⇒
      log.info("%s has picked up %s and %s and starts to eat".format(name, left.path.name, right.path.name))
      become(eating(left, right) orElse (clusterEvents))
      system.scheduler.scheduleOnce(5 seconds, self, Think)
    case Taken(`right`) if !waitingForLeft =>
      log.info("%s has picked up %s and %s and starts to eat".format(name, left.path.name, right.path.name))
      become(eating(left, right) orElse (clusterEvents))
      system.scheduler.scheduleOnce(5 seconds, self, Think)
    case Busy(chopstick) ⇒
      become(thinking(left, right) orElse (clusterEvents))
      if (waitingForLeft) {
        right ! Put(self)
      } else {
        left ! Put(self)
      }
      self ! Eat
    case Identify ⇒ identify("Waiting for Chopstick")
  }

  //When the results of the other grab comes back,
  //he needs to put it back if he got the other one.
  //Then go back and think and try to grab the chopsticks again
  def denied_a_chopstick(left: ActorRef, right: ActorRef): Receive = {
    case Taken(chopstick) ⇒
      become(thinking(left, right) orElse (clusterEvents))
      chopstick ! Put(self)
      self ! Eat
    case Busy(chopstick) ⇒
      become(thinking(left, right) orElse (clusterEvents))
      self ! Eat
    case Identify ⇒ identify("Denied a Chopstick")
  }

  //When a hakker is eating, he can decide to start to think,
  //then he puts down his chopsticks and starts to think
  def eating(left: ActorRef, right: ActorRef): Receive = {
    case Think ⇒
      become(thinking(left, right) orElse (clusterEvents))
      left ! Put(self)
      right ! Put(self)
      log.info("%s puts down his chopsticks and starts to think".format(name))
      system.scheduler.scheduleOnce(5 seconds, self, Eat)
    case Identify ⇒ identify("Eating")
  }

  def waitForChopsticks: Receive = {
    case (left: ActorRef, right: ActorRef) =>
      become(thinking(left, right) orElse (clusterEvents))
      system.scheduler.scheduleOnce(5 seconds, self, Eat)
  }

  def clusterEvents: Receive = {
    case state: CurrentClusterState ⇒ state.leader foreach updateTable
    case LeaderChanged(Some(leaderAddress)) ⇒ updateTable(leaderAddress)
  }

  def identify(busyWith: String) {
    sender ! Identification(name, busyWith)
  }

  def updateTable(leaderAdress: Address) {
    become(waitForChopsticks)
    context.actorFor(RootActorPath(leaderAdress) / "user" / "table") ! chair
  }

  //All hakkers start in a non-eating state
  def receive = clusterEvents

}
