/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import scala.concurrent.duration._
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.Address
import akka.actor.AddressTerminated
import akka.actor.Props
import akka.actor.RootActorPath
import akka.actor.Terminated
import akka.actor.ExtendedActorSystem
import akka.ConfigurationException
import akka.dispatch.{ UnboundedMessageQueueSemantics, RequiresMessageQueue }

/**
 * INTERNAL API
 */
private[akka] object RemoteWatcher {

  /**
   * Factory method for `RemoteWatcher` [[akka.actor.Props]].
   */
  def props(
    failureDetector: FailureDetectorRegistry[Address],
    heartbeatInterval: FiniteDuration,
    unreachableReaperInterval: FiniteDuration,
    heartbeatExpectedResponseAfter: FiniteDuration,
    numberOfEndHeartbeatRequests: Int): Props =
    Props(classOf[RemoteWatcher], failureDetector, heartbeatInterval, unreachableReaperInterval,
      heartbeatExpectedResponseAfter, numberOfEndHeartbeatRequests)

  case class WatchRemote(watchee: ActorRef, watcher: ActorRef)
  case class UnwatchRemote(watchee: ActorRef, watcher: ActorRef)

  @SerialVersionUID(1L) case object HeartbeatRequest
  @SerialVersionUID(1L) case object EndHeartbeatRequest
  @SerialVersionUID(1L) case class Heartbeat(addressUid: Int)

  // sent to self only
  case object HeartbeatTick
  case object ReapUnreachableTick
  case class ExpectedFirstHeartbeat(from: Address)

  // test purpose
  object Stats {
    lazy val empty: Stats = counts(0, 0, 0)
    def counts(watching: Int, watchingNodes: Int, watchedByNodes: Int): Stats =
      new Stats(watching, watchingNodes, watchedByNodes)(Set.empty)
  }
  case class Stats(watching: Int, watchingNodes: Int, watchedByNodes: Int)(val watchingRefs: Set[(ActorRef, ActorRef)]) {
    override def toString: String = {
      def formatWatchingRefs: String =
        if (watchingRefs.isEmpty) ""
        else ", watchingRefs=" + watchingRefs.map(x ⇒ x._2.path.name + " -> " + x._1.path.name).mkString("[", ", ", "]")

      s"Stats(watching=${watching}, watchingNodes=${watchingNodes}, watchedByNodes=${watchedByNodes}${formatWatchingRefs})"
    }
  }
}

/**
 * INTERNAL API
 *
 * Remote nodes with actors that are watched are monitored by this actor to be able
 * to detect network failures and JVM crashes. [[akka.remote.RemoteActorRefProvider]]
 * intercepts Watch and Unwatch system messages and sends corresponding
 * [[RemoteWatcher.WatchRemote]] and [[RemoteWatcher.UnwatchRemote]] to this actor.
 *
 * For a new node to be watched this actor starts the monitoring by sending [[RemoteWatcher.HeartbeatRequest]]
 * to the peer actor on the other node, which then sends periodic [[RemoteWatcher.Heartbeat]]
 * messages back. The failure detector on the watching side monitors these heartbeat messages.
 * If arrival of hearbeat messages stops it will be detected and this actor will publish
 * [[akka.actor.AddressTerminated]] to the `eventStream`.
 *
 * When all actors on a node have been unwatched, or terminated, this actor sends
 * [[RemoteWatcher.EndHeartbeatRequest]] messages to the peer actor on the other node,
 * which will then stop sending heartbeat messages.
 *
 * The actor sending heartbeat messages will also watch the peer on the other node,
 * to be able to stop sending heartbeat messages in case of network failure or JVM crash.
 *
 * For bi-directional watch between two nodes the same thing will be established in
 * both directions, but independent of each other.
 *
 */
private[akka] class RemoteWatcher(
  failureDetector: FailureDetectorRegistry[Address],
  heartbeatInterval: FiniteDuration,
  unreachableReaperInterval: FiniteDuration,
  heartbeatExpectedResponseAfter: FiniteDuration,
  numberOfEndHeartbeatRequests: Int)
  extends Actor with ActorLogging with RequiresMessageQueue[UnboundedMessageQueueSemantics] {

  import RemoteWatcher._
  import context.dispatcher
  def scheduler = context.system.scheduler

  val remoteProvider: RemoteActorRefProvider = context.system.asInstanceOf[ExtendedActorSystem].provider match {
    case rarp: RemoteActorRefProvider ⇒ rarp
    case other ⇒ throw new ConfigurationException(
      s"ActorSystem [${context.system}] needs to have a 'RemoteActorRefProvider' enabled in the configuration, currently uses [${other.getClass.getName}]")
  }

  val selfHeartbeatMsg = Heartbeat(AddressUidExtension(context.system).addressUid)

  // actors that this node is watching, tuple with (watcher, watchee)
  var watching: Set[(ActorRef, ActorRef)] = Set.empty
  // nodes that this node is watching, i.e. expecting hearteats from these nodes
  var watchingNodes: Set[Address] = Set.empty
  // heartbeats will be sent to watchedByNodes, ref is RemoteWatcher at other side
  var watchedByNodes: Set[ActorRef] = Set.empty
  var unreachable: Set[Address] = Set.empty
  var endWatchingNodes: Map[Address, Int] = Map.empty
  var addressUids: Map[Address, Int] = Map.empty

  val heartbeatTask = scheduler.schedule(heartbeatInterval, heartbeatInterval, self, HeartbeatTick)
  val failureDetectorReaperTask = scheduler.schedule(unreachableReaperInterval, unreachableReaperInterval,
    self, ReapUnreachableTick)

  override def postStop(): Unit = {
    super.postStop()
    heartbeatTask.cancel()
    failureDetectorReaperTask.cancel()
  }

  def receive = {
    case HeartbeatTick ⇒
      sendHeartbeat()
      sendHeartbeatRequest()
      sendEndHeartbeatRequest()
    case Heartbeat(uid)                  ⇒ heartbeat(uid)
    case ReapUnreachableTick             ⇒ reapUnreachable()
    case HeartbeatRequest                ⇒ heartbeatRequest()
    case EndHeartbeatRequest             ⇒ endHeartbeatRequest()
    case ExpectedFirstHeartbeat(from)    ⇒ triggerFirstHeartbeat(from)
    case WatchRemote(watchee, watcher)   ⇒ watchRemote(watchee, watcher)
    case UnwatchRemote(watchee, watcher) ⇒ unwatchRemote(watchee, watcher)
    case Terminated(watchee)             ⇒ terminated(watchee)

    // test purpose
    case Stats ⇒
      sender ! Stats(
        watching = watching.size,
        watchingNodes = watchingNodes.size,
        watchedByNodes = watchedByNodes.size)(watching)
  }

  def heartbeat(uid: Int): Unit = {
    val from = sender.path.address

    if (failureDetector.isMonitoring(from))
      log.debug("Received heartbeat from [{}]", from)
    else
      log.debug("Received first heartbeat from [{}]", from)

    if (watchingNodes(from) && !unreachable(from)) {
      addressUids += (from -> uid)
      failureDetector.heartbeat(from)
    }
  }

  def heartbeatRequest(): Unit = {
    // request to start sending heartbeats to the node
    log.debug("Received HeartbeatRequest from [{}]", sender.path.address)
    watchedByNodes += sender
    // watch back to stop heartbeating if other side dies
    context watch sender
    addWatching(sender, self)
  }

  def endHeartbeatRequest(): Unit = {
    // request to stop sending heartbeats to the node
    log.debug("Received EndHeartbeatRequest from [{}]", sender.path.address)
    watchedByNodes -= sender
    context unwatch sender
    watching -= ((sender, self))
    checkLastUnwatchOfNode(sender.path.address)
  }

  def reapUnreachable(): Unit =
    watchingNodes foreach { a ⇒
      if (!unreachable(a) && !failureDetector.isAvailable(a)) {
        log.warning("Detected unreachable: [{}]", a)
        addressUids.get(a) foreach { uid ⇒ quarantine(a, uid) }
        publishAddressTerminated(a)
        unreachable += a
      }
    }

  def publishAddressTerminated(address: Address): Unit =
    context.system.eventStream.publish(AddressTerminated(address))

  def quarantine(address: Address, uid: Int): Unit =
    remoteProvider.quarantine(address, uid)

  def watchRemote(watchee: ActorRef, watcher: ActorRef): Unit =
    if (watchee.path.uid == akka.actor.ActorCell.undefinedUid)
      logActorForDeprecationWarning(watchee)
    else if (watcher != self) {
      log.debug("Watching: [{} -> {}]", watcher.path, watchee.path)
      addWatching(watchee, watcher)

      // also watch from self, to be able to cleanup on termination of the watchee
      context watch watchee
      watching += ((watchee, self))
    }

  def addWatching(watchee: ActorRef, watcher: ActorRef): Unit = {
    watching += ((watchee, watcher))
    val watcheeAddress = watchee.path.address
    if (!watchingNodes(watcheeAddress) && unreachable(watcheeAddress)) {
      // first watch to that node after a previous unreachable
      unreachable -= watcheeAddress
      failureDetector.remove(watcheeAddress)
    }
    watchingNodes += watcheeAddress
    endWatchingNodes -= watcheeAddress
  }

  def unwatchRemote(watchee: ActorRef, watcher: ActorRef): Unit =
    if (watchee.path.uid == akka.actor.ActorCell.undefinedUid)
      logActorForDeprecationWarning(watchee)
    else if (watcher != self) {
      log.debug("Unwatching: [{} -> {}]", watcher.path, watchee.path)
      watching -= ((watchee, watcher))

      // clean up self watch when no more watchers of this watchee
      if (watching.forall { case (wee, wer) ⇒ wee != watchee || wer == self }) {
        log.debug("Cleanup self watch of [{}]", watchee.path)
        context unwatch watchee
        watching -= ((watchee, self))
      }
      checkLastUnwatchOfNode(watchee.path.address)
    }

  def logActorForDeprecationWarning(watchee: ActorRef): Unit = {
    log.debug("actorFor is deprecated, and watching a remote ActorRef acquired with actorFor is not reliable: [{}]", watchee.path)
  }

  def terminated(watchee: ActorRef): Unit = {
    if (matchingPathElements(self, watchee)) {
      log.debug("Other side terminated: [{}]", watchee.path)
      // stop heartbeating to that node immediately, and cleanup
      watchedByNodes -= watchee
      watching -= ((watchee, self))
    } else {
      log.debug("Watchee terminated: [{}]", watchee.path)
      watching = watching.filterNot {
        case (wee, _) ⇒ wee == watchee
      }
    }
    checkLastUnwatchOfNode(watchee.path.address)
  }

  def checkLastUnwatchOfNode(watcheeAddress: Address): Unit = {
    if (watchingNodes(watcheeAddress) && watching.forall {
      case (wee, wer) ⇒ wee.path.address != watcheeAddress || (wer == self && matchingPathElements(self, wee))
    }) {
      // unwatched last watchee on that node, not counting RemoteWatcher peer
      log.debug("Unwatched last watchee of node: [{}]", watcheeAddress)
      watchingNodes -= watcheeAddress
      addressUids -= watcheeAddress
      // continue by sending EndHeartbeatRequest for a while
      endWatchingNodes += (watcheeAddress -> 0)
      failureDetector.remove(watcheeAddress)
    }
  }

  def matchingPathElements(a: ActorRef, b: ActorRef): Boolean =
    a.path.elements == b.path.elements

  def sendHeartbeat(): Unit =
    watchedByNodes foreach { ref ⇒
      val a = ref.path.address
      if (!unreachable(a)) {
        log.debug("Sending Heartbeat to [{}]", ref.path.address)
        ref ! selfHeartbeatMsg
      }
    }

  def sendHeartbeatRequest(): Unit =
    watchingNodes.foreach { a ⇒
      if (!unreachable(a) && !failureDetector.isMonitoring(a)) {
        log.debug("Sending HeartbeatRequest to [{}]", a)
        context.actorSelection(RootActorPath(a) / self.path.elements) ! HeartbeatRequest
        // schedule the expected heartbeat for later, which will give the
        // other side a chance to start heartbeating, and also trigger some resends of
        // the heartbeat request
        scheduler.scheduleOnce(heartbeatExpectedResponseAfter, self, ExpectedFirstHeartbeat(a))
        endWatchingNodes -= a
      }
    }

  def sendEndHeartbeatRequest(): Unit =
    endWatchingNodes.foreach {
      case (a, count) ⇒
        if (!unreachable(a)) {
          log.debug("Sending EndHeartbeatRequest to [{}]", a)
          context.actorSelection(RootActorPath(a) / self.path.elements) ! EndHeartbeatRequest
        }
        if (count == numberOfEndHeartbeatRequests - 1) {
          endWatchingNodes -= a
        } else {
          endWatchingNodes += (a -> (count + 1))
        }
    }

  def triggerFirstHeartbeat(address: Address): Unit =
    if (watchingNodes(address) && !failureDetector.isMonitoring(address)) {
      log.debug("Trigger extra expected heartbeat from [{}]", address)
      failureDetector.heartbeat(address)
    }

}