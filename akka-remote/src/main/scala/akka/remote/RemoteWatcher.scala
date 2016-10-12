/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote

import akka.ConfigurationException
import akka.actor._
import akka.dispatch.sysmsg.{ DeathWatchNotification, Watch }
import akka.dispatch.{ RequiresMessageQueue, UnboundedMessageQueueSemantics }
import akka.event.AddressTerminatedTopic
import akka.remote.artery.ArteryMessage

import scala.collection.mutable
import scala.concurrent.duration._

/**
 * INTERNAL API
 */
private[akka] object RemoteWatcher {

  /**
   * Factory method for `RemoteWatcher` [[akka.actor.Props]].
   */
  def props(
    failureDetector:                FailureDetectorRegistry[Address],
    heartbeatInterval:              FiniteDuration,
    unreachableReaperInterval:      FiniteDuration,
    heartbeatExpectedResponseAfter: FiniteDuration): Props =
    Props(classOf[RemoteWatcher], failureDetector, heartbeatInterval, unreachableReaperInterval,
      heartbeatExpectedResponseAfter).withDeploy(Deploy.local)

  final case class WatchRemote(watchee: InternalActorRef, watcher: InternalActorRef)
  final case class UnwatchRemote(watchee: InternalActorRef, watcher: InternalActorRef)

  @SerialVersionUID(1L) case object Heartbeat extends HeartbeatMessage
  @SerialVersionUID(1L) final case class HeartbeatRsp(addressUid: Int) extends HeartbeatMessage

  // specific pair of messages for artery to allow for protobuf serialization and long uid
  case object ArteryHeartbeat extends HeartbeatMessage with ArteryMessage
  final case class ArteryHeartbeatRsp(uid: Long) extends HeartbeatMessage with ArteryMessage

  // sent to self only
  case object HeartbeatTick
  case object ReapUnreachableTick
  final case class ExpectedFirstHeartbeat(from: Address)

  // test purpose
  object Stats {
    lazy val empty: Stats = counts(0, 0)
    def counts(watching: Int, watchingNodes: Int): Stats = Stats(watching, watchingNodes)(Set.empty, Set.empty)
  }
  final case class Stats(watching: Int, watchingNodes: Int)(
    val watchingRefs:      Set[(ActorRef, ActorRef)],
    val watchingAddresses: Set[Address]) {
    override def toString: String = {
      def formatWatchingRefs: String =
        watchingRefs.map(x ⇒ x._2.path.name + " -> " + x._1.path.name).mkString("[", ", ", "]")
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
 * For a new node to be watched this actor periodically sends `RemoteWatcher.Heartbeat`
 * to the peer actor on the other node, which replies with [[RemoteWatcher.HeartbeatRsp]]
 * message back. The failure detector on the watching side monitors these heartbeat messages.
 * If arrival of heartbeat messages stops it will be detected and this actor will publish
 * [[akka.actor.AddressTerminated]] to the [[akka.event.AddressTerminatedTopic]].
 *
 * When all actors on a node have been unwatched it will stop sending heartbeat messages.
 *
 * For bi-directional watch between two nodes the same thing will be established in
 * both directions, but independent of each other.
 *
 */
private[akka] class RemoteWatcher(
  failureDetector:                FailureDetectorRegistry[Address],
  heartbeatInterval:              FiniteDuration,
  unreachableReaperInterval:      FiniteDuration,
  heartbeatExpectedResponseAfter: FiniteDuration)
  extends Actor with ActorLogging with RequiresMessageQueue[UnboundedMessageQueueSemantics] {

  import RemoteWatcher._
  import context.dispatcher
  def scheduler = context.system.scheduler

  val remoteProvider: RemoteActorRefProvider = RARP(context.system).provider
  val artery = remoteProvider.remoteSettings.Artery.Enabled

  val (heartBeatMsg, selfHeartbeatRspMsg) =
    if (artery) (ArteryHeartbeat, ArteryHeartbeatRsp(AddressUidExtension(context.system).longAddressUid))
    else (Heartbeat, HeartbeatRsp(AddressUidExtension(context.system).addressUid))

  // actors that this node is watching, map of watchee -> Set(watchers)
  val watching = new mutable.HashMap[InternalActorRef, mutable.Set[InternalActorRef]]() with mutable.MultiMap[InternalActorRef, InternalActorRef]

  // nodes that this node is watching, i.e. expecting heartbeats from these nodes. Map of address -> Set(watchee) on this address
  val watcheeByNodes = new mutable.HashMap[Address, mutable.Set[InternalActorRef]]() with mutable.MultiMap[Address, InternalActorRef]
  def watchingNodes = watcheeByNodes.keySet

  var unreachable: Set[Address] = Set.empty
  var addressUids: Map[Address, Long] = Map.empty

  val heartbeatTask = scheduler.schedule(heartbeatInterval, heartbeatInterval, self, HeartbeatTick)
  val failureDetectorReaperTask = scheduler.schedule(unreachableReaperInterval, unreachableReaperInterval,
    self, ReapUnreachableTick)

  override def postStop(): Unit = {
    super.postStop()
    heartbeatTask.cancel()
    failureDetectorReaperTask.cancel()
  }

  def receive = {
    case HeartbeatTick                             ⇒ sendHeartbeat()
    case Heartbeat | ArteryHeartbeat               ⇒ receiveHeartbeat()
    case HeartbeatRsp(uid)                         ⇒ receiveHeartbeatRsp(uid.toLong)
    case ArteryHeartbeatRsp(uid)                   ⇒ receiveHeartbeatRsp(uid)
    case ReapUnreachableTick                       ⇒ reapUnreachable()
    case ExpectedFirstHeartbeat(from)              ⇒ triggerFirstHeartbeat(from)
    case WatchRemote(watchee, watcher)             ⇒ addWatch(watchee, watcher)
    case UnwatchRemote(watchee, watcher)           ⇒ removeWatch(watchee, watcher)
    case t @ Terminated(watchee: InternalActorRef) ⇒ terminated(watchee, t.existenceConfirmed, t.addressTerminated)

    // test purpose
    case Stats ⇒
      val watchSet = watching.iterator.flatMap { case (wee, wers) ⇒ wers.map { wer ⇒ wee → wer } }.toSet[(ActorRef, ActorRef)]
      sender() ! Stats(
        watching = watchSet.size,
        watchingNodes = watchingNodes.size)(watchSet, watchingNodes.toSet)
  }

  def receiveHeartbeat(): Unit =
    sender() ! selfHeartbeatRspMsg

  def receiveHeartbeatRsp(uid: Long): Unit = {
    val from = sender().path.address

    if (failureDetector.isMonitoring(from))
      log.debug("Received heartbeat rsp from [{}]", from)
    else
      log.debug("Received first heartbeat rsp from [{}]", from)

    if (watcheeByNodes.contains(from) && !unreachable(from)) {
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
        quarantine(a, addressUids.get(a), "Deemed unreachable by remote failure detector")
        publishAddressTerminated(a)
        unreachable += a
      }
    }

  def publishAddressTerminated(address: Address): Unit =
    AddressTerminatedTopic(context.system).publish(AddressTerminated(address))

  def quarantine(address: Address, uid: Option[Long], reason: String): Unit =
    remoteProvider.quarantine(address, uid, reason)

  def addWatch(watchee: InternalActorRef, watcher: InternalActorRef): Unit = {
    assert(watcher != self)
    log.debug("Watching: [{} -> {}]", watcher.path, watchee.path)
    watching.addBinding(watchee, watcher)
    watchNode(watchee)

    // add watch from self, this will actually send a Watch to the target when necessary
    context watch watchee
  }

  def watchNode(watchee: InternalActorRef): Unit = {
    val watcheeAddress = watchee.path.address
    if (!watcheeByNodes.contains(watcheeAddress) && unreachable(watcheeAddress)) {
      // first watch to that node after a previous unreachable
      unreachable -= watcheeAddress
      failureDetector.remove(watcheeAddress)
    }
    watcheeByNodes.addBinding(watcheeAddress, watchee)
  }

  def removeWatch(watchee: InternalActorRef, watcher: InternalActorRef): Unit = {
    assert(watcher != self)
    log.debug("Unwatching: [{} -> {}]", watcher.path, watchee.path)

    // Could have used removeBinding, but it does not tell if this was the last entry. This saves a contains call.
    watching.get(watchee) match {
      case Some(watchers) ⇒
        watchers -= watcher
        if (watchers.isEmpty) {
          // clean up self watch when no more watchers of this watchee
          log.debug("Cleanup self watch of [{}]", watchee.path)
          context unwatch watchee
          removeWatchee(watchee)
        }
      case None ⇒
    }
  }

  def removeWatchee(watchee: InternalActorRef): Unit = {
    val watcheeAddress = watchee.path.address
    watching -= watchee
    // Could have used removeBinding, but it does not tell if this was the last entry. This saves a contains call.
    watcheeByNodes.get(watcheeAddress) match {
      case Some(watchees) ⇒
        watchees -= watchee
        if (watchees.isEmpty) {
          // unwatched last watchee on that node
          log.debug("Unwatched last watchee of node: [{}]", watcheeAddress)
          unwatchNode(watcheeAddress)
        }
      case None ⇒
    }
  }

  def unwatchNode(watcheeAddress: Address): Unit = {
    watcheeByNodes -= watcheeAddress
    addressUids -= watcheeAddress
    failureDetector.remove(watcheeAddress)
  }

  def terminated(watchee: InternalActorRef, existenceConfirmed: Boolean, addressTerminated: Boolean): Unit = {
    log.debug("Watchee terminated: [{}]", watchee.path)

    // When watchee is stopped it sends DeathWatchNotification to this RemoteWatcher,
    // which will propagate it to all watchers of this watchee.
    // addressTerminated case is already handled by the watcher itself in DeathWatch trait
    if (!addressTerminated)
      for {
        watchers ← watching.get(watchee)
        watcher ← watchers
      } watcher.sendSystemMessage(DeathWatchNotification(watchee, existenceConfirmed, addressTerminated))

    removeWatchee(watchee)
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
        context.actorSelection(RootActorPath(a) / self.path.elements) ! heartBeatMsg
      }
    }

  def triggerFirstHeartbeat(address: Address): Unit =
    if (watcheeByNodes.contains(address) && !failureDetector.isMonitoring(address)) {
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
      watchees ← watcheeByNodes.get(address)
      watchee ← watchees
    } {
      val watcher = self.asInstanceOf[InternalActorRef]
      log.debug("Re-watch [{} -> {}]", watcher.path, watchee.path)
      watchee.sendSystemMessage(Watch(watchee, watcher)) // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
    }
}
