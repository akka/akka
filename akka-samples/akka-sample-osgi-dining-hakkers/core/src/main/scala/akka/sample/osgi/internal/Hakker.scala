package akka.sample.osgi.internal

import language.postfixOps
import scala.concurrent.duration._
import akka.actor.Terminated
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.{ CurrentClusterState, LeaderChanged }
import akka.event.Logging
import akka.sample.osgi.api._
import akka.actor.{ RootActorPath, Address, ActorRef, Actor }
import akka.sample.osgi.api.SubscribeToHakkerStateChanges
import akka.sample.osgi.api.HakkerStateChange

//Akka adaptation of
//http://www.dalnefre.com/wp/2010/08/dining-philosophers-in-humus/

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
    case Take(otherHakker) =>
      otherHakker ! Busy(self)
    case Put(`hakker`) =>
      become(available)
  }

  //When a Chopstick is available, it can be taken by a hakker
  def available: Receive = {
    case Take(hakker) =>
      log.info(self.path + " is taken by " + hakker)
      become(takenBy(hakker))
      hakker ! Taken(self)
  }

  //A Chopstick begins its existence as available
  def receive = available
}

/*
* A hakker is an awesome dude or dudette who either thinks about hacking or has to eat ;-)
*/
class Hakker(name: String, chair: Int) extends Actor {

  val log = Logging(context.system, this)

  log.info("Created Hakker at" + self.path)

  import context._

  val cluster = Cluster(context.system)

  override def preStart() {
    log.info(s"Hakker ($name) takes position($chair)")
    cluster.subscribe(self, classOf[LeaderChanged])
  }

  override def postStop() {
    log.info(s"Hakker ($name) leaves position($chair)")
    cluster.unsubscribe(self)
  }

  var subscribers = Set.empty[ActorRef]

  //When a hakker is thinking it can become hungry
  //and try to pick up its chopsticks and eat
  def thinking(left: ActorRef, right: ActorRef): Receive = {
    case Eat =>
      pubStateChange("thinking", "hungry")
      become(hungry(left, right) orElse (managementEvents))
      left ! Take(self)
      right ! Take(self)
    case Identify => identify("Thinking")
  }

  //When a hakker is hungry it tries to pick up its chopsticks and eat
  //When it picks one up, it goes into wait for the other
  //If the hakkers first attempt at grabbing a chopstick fails,
  //it starts to wait for the response of the other grab
  def hungry(left: ActorRef, right: ActorRef): Receive = {
    case Taken(`left`) =>
      pubStateChange("hungry", "waiting")
      become(waiting_for(left, right, false) orElse (managementEvents))
    case Taken(`right`) =>
      pubStateChange("hungry", "waiting")
      become(waiting_for(left, right, true) orElse (managementEvents))
    case Busy(chopstick) =>
      pubStateChange("hungry", "denied_a_chopstick")
      become(denied_a_chopstick(left, right) orElse (managementEvents))
    case Identify => identify("Hungry")
  }

  //When a hakker is waiting for the last chopstick it can either obtain it
  //and start eating, or the other chopstick was busy, and the hakker goes
  //back to think about how he should obtain his chopsticks :-)
  def waiting_for(left: ActorRef, right: ActorRef, waitingForLeft: Boolean): Receive = {
    case Taken(`left`) if waitingForLeft =>
      log.info("%s has picked up %s and %s and starts to eat".format(name, left.path.name, right.path.name))
      pubStateChange("waiting", "eating")
      become(eating(left, right) orElse (managementEvents))
      system.scheduler.scheduleOnce(5 seconds, self, Think)
    case Taken(`right`) if !waitingForLeft =>
      log.info("%s has picked up %s and %s and starts to eat".format(name, left.path.name, right.path.name))
      pubStateChange("waiting", "eating")
      become(eating(left, right) orElse (managementEvents))
      system.scheduler.scheduleOnce(5 seconds, self, Think)
    case Busy(chopstick) =>
      pubStateChange("waiting", "thinking")
      become(thinking(left, right) orElse (managementEvents))
      if (waitingForLeft) {
        right ! Put(self)
      } else {
        left ! Put(self)
      }
      self ! Eat
    case Identify => identify("Waiting for Chopstick")
  }

  //When the results of the other grab comes back,
  //he needs to put it back if he got the other one.
  //Then go back and think and try to grab the chopsticks again
  def denied_a_chopstick(left: ActorRef, right: ActorRef): Receive = {
    case Taken(chopstick) =>
      pubStateChange("denied_a_chopstick", "thinking")
      become(thinking(left, right) orElse (managementEvents))
      chopstick ! Put(self)
      self ! Eat
    case Busy(chopstick) =>
      pubStateChange("denied_a_chopstick", "thinking")
      become(thinking(left, right) orElse (managementEvents))
      self ! Eat
    case Identify => identify("Denied a Chopstick")
  }

  //When a hakker is eating, he can decide to start to think,
  //then he puts down his chopsticks and starts to think
  def eating(left: ActorRef, right: ActorRef): Receive = {
    case Think =>
      pubStateChange("eating", "thinking")
      become(thinking(left, right) orElse (managementEvents))
      left ! Put(self)
      right ! Put(self)
      log.info("%s puts down his chopsticks and starts to think".format(name))
      system.scheduler.scheduleOnce(5 seconds, self, Eat)
    case Identify => identify("Eating")
  }

  def waitForChopsticks: Receive = {
    case (left: ActorRef, right: ActorRef) =>
      pubStateChange("waiting", "thinking")
      become(thinking(left, right) orElse managementEvents)
      system.scheduler.scheduleOnce(5 seconds, self, Eat)
    case Identify => identify("Waiting")
  }

  def managementEvents: Receive = {
    case state: CurrentClusterState         => state.leader foreach updateTable
    case LeaderChanged(Some(leaderAddress)) => updateTable(leaderAddress)
    case SubscribeToHakkerStateChanges =>
      subscribers += sender()
      context watch sender()
    case Terminated(subscriber) =>
      subscribers -= subscriber
  }

  def initializing: Receive = {
    case Identify => identify("Initializing")
  }

  def identify(busyWith: String): Unit = {
    sender() ! Identification(name, busyWith)
  }

  def updateTable(leaderAdress: Address): Unit = {
    pubStateChange("-", "waiting")
    become(waitForChopsticks orElse managementEvents)
    context.actorSelection(RootActorPath(leaderAdress) / "user" / "table") ! chair
  }

  //All hakkers start in a non-eating state
  def receive = initializing orElse managementEvents

  def pubStateChange(from: String, to: String): Unit = {
    val chg = HakkerStateChange(name, from, to)
    subscribers foreach { _ ! chg }
  }

}
