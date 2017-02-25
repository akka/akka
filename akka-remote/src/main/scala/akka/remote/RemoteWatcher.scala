/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
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
import akka.actor.InternalActorRef
import akka.dispatch.sysmsg.DeathWatchNotification
import akka.dispatch.sysmsg.Watch
import akka.actor.Deploy
import akka.event.AddressTerminatedTopic

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
    heartbeatExpectedResponseAfter: FiniteDuration): Props =
    Props(classOf[RemoteWatcher], failureDetector, heartbeatInterval, unreachableReaperInterval,
      heartbeatExpectedResponseAfter).withDeploy(Deploy.local)

  case class WatchRemote(watchee: ActorRef, watcher: ActorRef)
  case class UnwatchRemote(watchee: ActorRef, watcher: ActorRef)

  @SerialVersionUID(1L) case object Heartbeat extends PriorityMessage
  @SerialVersionUID(1L) case class HeartbeatRsp(addressUid: Int) extends PriorityMessage

  // sent to self only
  case object HeartbeatTick
  case object ReapUnreachableTick
  case class ExpectedFirstHeartbeat(from: Address)

  // test purpose
  object Stats {
    lazy val empty: Stats = counts(0, 0)
    def counts(watching: Int, watchingNodes: Int): Stats =
      new Stats(watching, watchingNodes)(Map.empty, Set.empty)
  }
  case class Stats(watching: Int, watchingNodes: Int)(val watchingRefs: Map[ActorRef, Set[ActorRef]], val watchingAddresses: Set[Address]) {
    override def toString: String = {
      def formatWatchingRefs: String =
        watchingRefs.flatMap(x ⇒ x._2.map(_.path.name + " -> " + x._1.path.name)).mkString("[", ", ", "]")
      def formatWatchingAddresses: String =
        watchingAddresses.mkString("[", ", ", "]")

      s"Stats(watching=$watching, watchingNodes=$watchingNodes, watchingRefs=$formatWatchingRefs, watchingAddresses=$formatWatchingAddresses)"
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
 * For a new node to be watched this actor periodically sends [[RemoteWatcher.Heartbeat]]
 * to the peer actor on the other node, which replies with [[RemoteWatcher.HeartbeatRsp]]
 * message back. The failure detector on the watching side monitors these heartbeat messages.
 * If arrival of hearbeat messages stops it will be detected and this actor will publish
 * [[akka.actor.AddressTerminated]] to the [[akka.event.AddressTerminatedTopic]].
 *
 * When all actors on a node have been unwatched it will stop sending heartbeat messages.
 *
 * For bi-directional watch between two nodes the same thing will be established in
 * both directions, but independent of each other.
 *
 */
private[akka] class RemoteWatcher(
  failureDetector: FailureDetectorRegistry[Address],
  heartbeatInterval: FiniteDuration,
  unreachableReaperInterval: FiniteDuration,
  heartbeatExpectedResponseAfter: FiniteDuration)
  extends Actor with ActorLogging with RequiresMessageQueue[UnboundedMessageQueueSemantics] {

  import RemoteWatcher._
  import context.dispatcher
  def scheduler = context.system.scheduler

  val remoteProvider: RemoteActorRefProvider = context.system.asInstanceOf[ExtendedActorSystem].provider match {
    case rarp: RemoteActorRefProvider ⇒ rarp
    case other ⇒ throw new ConfigurationException(
      s"ActorSystem [${context.system}] needs to have a 'RemoteActorRefProvider' enabled in the configuration, currently uses [${other.getClass.getName}]")
  }

  val selfHeartbeatRspMsg = HeartbeatRsp(AddressUidExtension(context.system).addressUid)

  // actors that this node is watching, map of watchee -> Set(watchers)
  var watching: Map[ActorRef, Set[ActorRef]] = Map.empty
  // nodes that this node is watching, i.e. expecting hearteats from these nodes
  var watchingNodes: Set[Address] = Set.empty
  var unreachable: Set[Address] = Set.empty
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
    case HeartbeatTick                   ⇒ sendHeartbeat()
    case Heartbeat                       ⇒ receiveHeartbeat()
    case HeartbeatRsp(uid)               ⇒ receiveHeartbeatRsp(uid)
    case ReapUnreachableTick             ⇒ reapUnreachable()
    case ExpectedFirstHeartbeat(from)    ⇒ triggerFirstHeartbeat(from)
    case WatchRemote(watchee, watcher)   ⇒ watchRemote(watchee, watcher)
    case UnwatchRemote(watchee, watcher) ⇒ unwatchRemote(watchee, watcher)
    case t @ Terminated(watchee)         ⇒ terminated(watchee, t.existenceConfirmed, t.addressTerminated)

    // test purpose
    case Stats ⇒
      sender() ! Stats(
        watching = watching.foldLeft(0) { case (acc, (_, wers)) ⇒ acc + wers.size },
        watchingNodes = watchingNodes.size)(watching, watchingNodes)
  }

  def receiveHeartbeat(): Unit =
    sender() ! selfHeartbeatRspMsg

  def receiveHeartbeatRsp(uid: Int): Unit = {
    val from = sender().path.address

    if (failureDetector.isMonitoring(from))
      log.debug("Received heartbeat rsp from [{}]", from)
    else
      log.debug("Received first heartbeat rsp from [{}]", from)

    if (watchingNodes(from) && !unreachable(from)) {
      if (!addressUids.contains(from) || addressUids(from) != uid)
        reWatch(from)
      addressUids += (from → uid)
      failureDetector.heartbeat(from)
    }
  }

  def reapUnreachable(): Unit =
    watchingNodes foreach { a ⇒
      if (!unreachable(a) && !failureDetector.isAvailable(a)) {
        log.warning("Detected unreachable: [{}]", a)
        quarantine(a, addressUids.get(a))
        publishAddressTerminated(a)
        unreachable += a
      }
    }

  def publishAddressTerminated(address: Address): Unit =
    AddressTerminatedTopic(context.system).publish(AddressTerminated(address))

  def quarantine(address: Address, uid: Option[Int]): Unit =
    remoteProvider.quarantine(address, uid)

  def watchRemote(watchee: ActorRef, watcher: ActorRef): Unit = {
    if (watchee.path.uid == akka.actor.ActorCell.undefinedUid)
      logActorForDeprecationWarning(watchee)
    log.debug("Watching: [{} -> {}]", watcher.path, watchee.path)
    insertWatch(watchee, watcher)
    watchNode(watchee.path.address)

    // add watch from self, this will actually send a Watch to the target when necessary
    context watch watchee
  }

  def watchNode(watcheeAddress: Address): Unit = {
    if (!watchingNodes(watcheeAddress) && unreachable(watcheeAddress)) {
      // first watch to that node after a previous unreachable
      unreachable -= watcheeAddress
      failureDetector.remove(watcheeAddress)
    }
    watchingNodes += watcheeAddress
  }

  def insertWatch(watchee: ActorRef, watcher: ActorRef): Unit = {
    val newWatchers = watching.get(watchee) match {
      case Some(watchers) ⇒ watchers + watcher
      case None ⇒ Set(watcher)
    }
    watching += watchee → newWatchers
  }

  def unwatchRemote(watchee: ActorRef, watcher: ActorRef): Unit =
    if (watchee.path.uid == akka.actor.ActorCell.undefinedUid)
      logActorForDeprecationWarning(watchee)
    else {
      log.debug("Unwatching: [{} -> {}]", watcher.path, watchee.path)
      watching.get(watchee) match {
        case Some(watchers) if watchers == Set(watcher) ⇒
          clearAllWatches(watchee)
          checkLastUnwatchOfNode(watchee.path.address)
        case Some(watchers) ⇒
          watching += watchee → (watchers - watcher)
        case None ⇒
      }
    }

  def clearAllWatches(watchee: ActorRef): Unit = {
    // clean up self watch when no more watchers of this watchee
    log.debug("Cleanup self watch of [{}]", watchee.path)
    context unwatch watchee
    watching -= watchee
  }

  def logActorForDeprecationWarning(watchee: ActorRef): Unit = {
    log.debug("actorFor is deprecated, and watching a remote ActorRef acquired with actorFor is not reliable: [{}]", watchee.path)
  }

  def terminated(watchee: ActorRef, existenceConfirmed: Boolean, addressTerminated: Boolean): Unit = {
    log.debug("Watchee terminated: [{}]", watchee.path)

    // When watchee is stopped it sends DeathWatchNotification to this RemoteWatcher,
    // which will propagate it to all watchers of this watchee.
    // addressTerminated case is already handled by the watcher itself in DeathWatch trai
    if (!addressTerminated && watching.contains(watchee))
      watching(watchee).foreach {
        case wer: InternalActorRef ⇒
          wer.sendSystemMessage(DeathWatchNotification(watchee, existenceConfirmed, addressTerminated))
      }
    watching -= watchee

    checkLastUnwatchOfNode(watchee.path.address)
  }

  def checkLastUnwatchOfNode(watcheeAddress: Address): Unit = {
    if (watchingNodes(watcheeAddress) && watching.keys.forall(_.path.address != watcheeAddress)) {
      // unwatched last watchee on that node
      log.debug("Unwatched last watchee of node: [{}]", watcheeAddress)
      unwatchNode(watcheeAddress)
    }
  }

  def unwatchNode(watcheeAddress: Address): Unit = {
    watchingNodes -= watcheeAddress
    addressUids -= watcheeAddress
    failureDetector.remove(watcheeAddress)
  }

  def sendHeartbeat(): Unit =
    watchingNodes foreach { a ⇒
      if (!unreachable(a)) {
        if (failureDetector.isMonitoring(a)) {
          log.debug("Sending Heartbeat to [{}]", a)
        } else {
          log.debug("Sending first Heartbeat to [{}]", a)
          // schedule the expected first heartbeat for later, which will give the
          // other side a chance to reply, and also trigger some resends if needed
          scheduler.scheduleOnce(heartbeatExpectedResponseAfter, self, ExpectedFirstHeartbeat(a))
        }
        context.actorSelection(RootActorPath(a) / self.path.elements) ! Heartbeat
      }
    }

  def triggerFirstHeartbeat(address: Address): Unit =
    if (watchingNodes(address) && !failureDetector.isMonitoring(address)) {
      log.debug("Trigger extra expected heartbeat from [{}]", address)
      failureDetector.heartbeat(address)
    }

  /**
   * To ensure that we receive heartbeat messages from the right actor system
   * incarnation we send Watch again for the first HeartbeatRsp (containing
   * the system UID) and if HeartbeatRsp contains a new system UID.
   * Terminated will be triggered if the watchee (including correct Actor UID)
   * does not exist.
   */
  def reWatch(address: Address): Unit =
    for {
      wee ← watching.keys if wee.path.address == address
      (watchee: InternalActorRef, watcher: InternalActorRef) = (wee, self)
    } {
      log.debug("Re-watch [{} -> {}]", watcher.path, watchee.path)
      watchee.sendSystemMessage(Watch(watchee, watcher)) // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
    }

}