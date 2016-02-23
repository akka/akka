/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.cluster.singleton

import com.typesafe.config.Config
import scala.concurrent.duration._
import scala.collection.immutable
import akka.actor.Actor
import akka.actor.Deploy
import akka.actor.ActorSystem
import akka.actor.ActorRef
import akka.actor.ActorSelection
import akka.actor.Address
import akka.actor.DeadLetterSuppression
import akka.actor.FSM
import akka.actor.Props
import akka.actor.Terminated
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.cluster.Member
import akka.cluster.MemberStatus
import akka.AkkaException
import akka.actor.NoSerializationVerificationNeeded

object ClusterSingletonManagerSettings {

  /**
   * Create settings from the default configuration
   * `akka.cluster.singleton`.
   */
  def apply(system: ActorSystem): ClusterSingletonManagerSettings =
    apply(system.settings.config.getConfig("akka.cluster.singleton"))
      .withRemovalMargin(Cluster(system).settings.DownRemovalMargin)

  /**
   * Create settings from a configuration with the same layout as
   * the default configuration `akka.cluster.singleton`.
   */
  def apply(config: Config): ClusterSingletonManagerSettings =
    new ClusterSingletonManagerSettings(
      singletonName = config.getString("singleton-name"),
      role = roleOption(config.getString("role")),
      removalMargin = Duration.Zero, // defaults to ClusterSettins.DownRemovalMargin
      handOverRetryInterval = config.getDuration("hand-over-retry-interval", MILLISECONDS).millis)

  /**
   * Java API: Create settings from the default configuration
   * `akka.cluster.singleton`.
   */
  def create(system: ActorSystem): ClusterSingletonManagerSettings = apply(system)

  /**
   * Java API: Create settings from a configuration with the same layout as
   * the default configuration `akka.cluster.singleton`.
   */
  def create(config: Config): ClusterSingletonManagerSettings = apply(config)

  /**
   * INTERNAL API
   */
  private[akka] def roleOption(role: String): Option[String] =
    if (role == "") None else Option(role)

}

/**
 * @param singletonName The actor name of the child singleton actor.
 *
 * @param role Singleton among the nodes tagged with specified role.
 *   If the role is not specified it's a singleton among all nodes in
 *   the cluster.
 *
 * @param removalMargin Margin until the singleton instance that belonged to
 *   a downed/removed partition is created in surviving partition. The purpose of
 *   this margin is that in case of a network partition the singleton actors
 *   in the non-surviving partitions must be stopped before corresponding actors
 *   are started somewhere else. This is especially important for persistent
 *   actors.
 *
 * @param handOverRetryInterval When a node is becoming oldest it sends hand-over
 *   request to previous oldest, that might be leaving the cluster. This is
 *   retried with this interval until the previous oldest confirms that the hand
 *   over has started or the previous oldest member is removed from the cluster
 *   (+ `removalMargin`).
 */
final class ClusterSingletonManagerSettings(
  val singletonName: String,
  val role: Option[String],
  val removalMargin: FiniteDuration,
  val handOverRetryInterval: FiniteDuration) extends NoSerializationVerificationNeeded {

  def withSingletonName(name: String): ClusterSingletonManagerSettings = copy(singletonName = name)

  def withRole(role: String): ClusterSingletonManagerSettings = copy(role = ClusterSingletonManagerSettings.roleOption(role))

  def withRole(role: Option[String]) = copy(role = role)

  def withRemovalMargin(removalMargin: FiniteDuration): ClusterSingletonManagerSettings =
    copy(removalMargin = removalMargin)

  def withHandOverRetryInterval(retryInterval: FiniteDuration): ClusterSingletonManagerSettings =
    copy(handOverRetryInterval = retryInterval)

  private def copy(singletonName: String = singletonName,
                   role: Option[String] = role,
                   removalMargin: FiniteDuration = removalMargin,
                   handOverRetryInterval: FiniteDuration = handOverRetryInterval): ClusterSingletonManagerSettings =
    new ClusterSingletonManagerSettings(singletonName, role, removalMargin, handOverRetryInterval)
}

/**
 * Marker trait for remote messages with special serializer.
 */
sealed trait ClusterSingletonMessage extends Serializable

object ClusterSingletonManager {

  /**
   * Scala API: Factory method for `ClusterSingletonManager` [[akka.actor.Props]].
   */
  def props(
    singletonProps: Props,
    terminationMessage: Any,
    settings: ClusterSingletonManagerSettings): Props =
    Props(new ClusterSingletonManager(singletonProps, terminationMessage, settings)).withDeploy(Deploy.local)

  /**
   * INTERNAL API
   * public due to the `with FSM` type parameters
   */
  sealed trait State
  /**
   * INTERNAL API
   * public due to the `with FSM` type parameters
   */
  sealed trait Data

  /**
   * INTERNAL API
   */
  private[akka] object Internal {
    /**
     * Sent from new oldest to previous oldest to initiate the
     * hand-over process. `HandOverInProgress` and `HandOverDone`
     * are expected replies.
     */
    case object HandOverToMe extends ClusterSingletonMessage with DeadLetterSuppression
    /**
     * Confirmation by the previous oldest that the hand
     * over process, shut down of the singleton actor, has
     * started.
     */
    case object HandOverInProgress extends ClusterSingletonMessage
    /**
     * Confirmation by the previous oldest that the singleton
     * actor has been terminated and the hand-over process is
     * completed.
     */
    case object HandOverDone extends ClusterSingletonMessage
    /**
     * Sent from from previous oldest to new oldest to
     * initiate the normal hand-over process.
     * Especially useful when new node joins and becomes
     * oldest immediately, without knowing who was previous
     * oldest.
     */
    case object TakeOverFromMe extends ClusterSingletonMessage with DeadLetterSuppression

    final case class HandOverRetry(count: Int)
    final case class TakeOverRetry(count: Int)
    case object Cleanup
    case object StartOldestChangedBuffer

    case object Start extends State
    case object Oldest extends State
    case object Younger extends State
    case object BecomingOldest extends State
    case object WasOldest extends State
    case object HandingOver extends State
    case object TakeOver extends State
    case object End extends State

    case object Uninitialized extends Data
    final case class YoungerData(oldestOption: Option[Address]) extends Data
    final case class BecomingOldestData(previousOldestOption: Option[Address]) extends Data
    final case class OldestData(singleton: ActorRef, singletonTerminated: Boolean = false) extends Data
    final case class WasOldestData(singleton: ActorRef, singletonTerminated: Boolean,
                                   newOldestOption: Option[Address]) extends Data
    final case class HandingOverData(singleton: ActorRef, handOverTo: Option[ActorRef]) extends Data
    case object EndData extends Data
    final case class DelayedMemberRemoved(member: Member)

    val HandOverRetryTimer = "hand-over-retry"
    val TakeOverRetryTimer = "take-over-retry"
    val CleanupTimer = "cleanup"

    object OldestChangedBuffer {
      /**
       * Request to deliver one more event.
       */
      case object GetNext
      /**
       * The first event, corresponding to CurrentClusterState.
       */
      final case class InitialOldestState(oldest: Option[Address], safeToBeOldest: Boolean)

      final case class OldestChanged(oldest: Option[Address])
    }

    /**
     * Notifications of member events that track oldest member is tunneled
     * via this actor (child of ClusterSingletonManager) to be able to deliver
     * one change at a time. Avoiding simultaneous changes simplifies
     * the process in ClusterSingletonManager. ClusterSingletonManager requests
     * next event with `GetNext` when it is ready for it. Only one outstanding
     * `GetNext` request is allowed. Incoming events are buffered and delivered
     * upon `GetNext` request.
     */
    class OldestChangedBuffer(role: Option[String]) extends Actor {
      import OldestChangedBuffer._

      val cluster = Cluster(context.system)
      // sort by age, oldest first
      val ageOrdering = Member.ageOrdering
      var membersByAge: immutable.SortedSet[Member] = immutable.SortedSet.empty(ageOrdering)

      var changes = Vector.empty[AnyRef]

      // subscribe to MemberEvent, re-subscribe when restart
      override def preStart(): Unit = {
        cluster.subscribe(self, classOf[MemberEvent])
      }
      override def postStop(): Unit = cluster.unsubscribe(self)

      def matchingRole(member: Member): Boolean = role match {
        case None    ⇒ true
        case Some(r) ⇒ member.hasRole(r)
      }

      def trackChange(block: () ⇒ Unit): Unit = {
        val before = membersByAge.headOption
        block()
        val after = membersByAge.headOption
        if (before != after)
          changes :+= OldestChanged(after.map(_.address))
      }

      def handleInitial(state: CurrentClusterState): Unit = {
        membersByAge = immutable.SortedSet.empty(ageOrdering) union state.members.filter(m ⇒
          (m.status == MemberStatus.Up || m.status == MemberStatus.Leaving) && matchingRole(m))
        val safeToBeOldest = !state.members.exists { m ⇒ (m.status == MemberStatus.Down || m.status == MemberStatus.Exiting) }
        val initial = InitialOldestState(membersByAge.headOption.map(_.address), safeToBeOldest)
        changes :+= initial
      }

      def add(m: Member): Unit = {
        if (matchingRole(m))
          trackChange { () ⇒
            membersByAge -= m // replace
            membersByAge += m
          }
      }

      def remove(m: Member): Unit = {
        if (matchingRole(m))
          trackChange { () ⇒ membersByAge -= m }
      }

      def sendFirstChange(): Unit = {
        val event = changes.head
        changes = changes.tail
        context.parent ! event
      }

      def receive = {
        case state: CurrentClusterState ⇒ handleInitial(state)
        case MemberUp(m)                ⇒ add(m)
        case mEvent: MemberEvent if (mEvent.isInstanceOf[MemberExited] || mEvent.isInstanceOf[MemberRemoved]) ⇒
          remove(mEvent.member)
        case GetNext if changes.isEmpty ⇒
          context.become(deliverNext, discardOld = false)
        case GetNext ⇒
          sendFirstChange()
      }

      // the buffer was empty when GetNext was received, deliver next event immediately
      def deliverNext: Actor.Receive = {
        case state: CurrentClusterState ⇒
          handleInitial(state)
          sendFirstChange()
          context.unbecome()
        case MemberUp(m) ⇒
          add(m)
          if (changes.nonEmpty) {
            sendFirstChange()
            context.unbecome()
          }
        case mEvent: MemberEvent if (mEvent.isInstanceOf[MemberExited] || mEvent.isInstanceOf[MemberRemoved]) ⇒
          remove(mEvent.member)
          if (changes.nonEmpty) {
            sendFirstChange()
            context.unbecome()
          }
      }

    }

  }
}

/**
 * Thrown when a consistent state can't be determined within the
 * defined retry limits. Eventually it will reach a stable state and
 * can continue, and that is simplified by starting over with a clean
 * state. Parent supervisor should typically restart the actor, i.e.
 * default decision.
 */
class ClusterSingletonManagerIsStuck(message: String) extends AkkaException(message, null)

/**
 * Manages singleton actor instance among all cluster nodes or a group
 * of nodes tagged with a specific role. At most one singleton instance
 * is running at any point in time.
 *
 * The ClusterSingletonManager is supposed to be started on all nodes,
 * or all nodes with specified role, in the cluster with `actorOf`.
 * The actual singleton is started on the oldest node by creating a child
 * actor from the supplied `singletonProps`.
 *
 * The singleton actor is always running on the oldest member with specified role.
 * The oldest member is determined by [[akka.cluster.Member#isOlderThan]].
 * This can change when removing members. A graceful hand over can normally
 * be performed when current oldest node is leaving the cluster. Be aware that
 * there is a short time period when there is no active singleton during the
 * hand-over process.
 *
 * The cluster failure detector will notice when oldest node
 * becomes unreachable due to things like JVM crash, hard shut down,
 * or network failure. When the crashed node has been removed (via down) from the
 * cluster then a new oldest node will take over and a new singleton actor is
 * created. For these failure scenarios there will not be a graceful hand-over,
 * but more than one active singletons is prevented by all reasonable means. Some
 * corner cases are eventually resolved by configurable timeouts.
 *
 * You access the singleton actor with [[ClusterSingletonProxy]].
 * Alternatively the singleton actor may broadcast its existence when it is started.
 *
 * Use factory method [[ClusterSingletonManager#props]] to create the
 * [[akka.actor.Props]] for the actor.
 *
 *
 * @param singletonProps [[akka.actor.Props]] of the singleton actor instance.
 *
 * @param terminationMessage When handing over to a new oldest node
 *   this `terminationMessage` is sent to the singleton actor to tell
 *   it to finish its work, close resources, and stop.
 *   The hand-over to the new oldest node is completed when the
 *   singleton actor is terminated.
 *   Note that [[akka.actor.PoisonPill]] is a perfectly fine
 *   `terminationMessage` if you only need to stop the actor.
 *
 * @param settings see [[ClusterSingletonManagerSettings]]
 */
class ClusterSingletonManager(
  singletonProps: Props,
  terminationMessage: Any,
  settings: ClusterSingletonManagerSettings)
  extends Actor with FSM[ClusterSingletonManager.State, ClusterSingletonManager.Data] {

  import ClusterSingletonManager.Internal._
  import ClusterSingletonManager.Internal.OldestChangedBuffer._
  import settings._

  val cluster = Cluster(context.system)
  val selfAddressOption = Some(cluster.selfAddress)
  import cluster.settings.LogInfo

  require(role.forall(cluster.selfRoles.contains),
    s"This cluster member [${cluster.selfAddress}] doesn't have the role [$role]")

  val removalMargin =
    if (settings.removalMargin <= Duration.Zero) cluster.settings.DownRemovalMargin
    else settings.removalMargin

  val (maxHandOverRetries, maxTakeOverRetries) = {
    val n = (removalMargin.toMillis / handOverRetryInterval.toMillis).toInt
    val minRetries = context.system.settings.config.getInt(
      "akka.cluster.singleton.min-number-of-hand-over-retries")
    require(minRetries >= 1, "min-number-of-hand-over-retries must be >= 1")
    val handOverRetries = math.max(minRetries, n + 3)
    val takeOverRetries = math.max(1, handOverRetries - 3)

    (handOverRetries, takeOverRetries)
  }

  // started when when self member is Up
  var oldestChangedBuffer: ActorRef = _
  // Previous GetNext request delivered event and new GetNext is to be sent
  var oldestChangedReceived = true

  var selfExited = false

  // keep track of previously removed members
  var removed = Map.empty[Address, Deadline]

  def addRemoved(address: Address): Unit =
    removed += address -> (Deadline.now + 15.minutes)

  def cleanupOverdueNotMemberAnyMore(): Unit = {
    removed = removed filter { case (address, deadline) ⇒ deadline.hasTimeLeft }
  }

  def logInfo(message: String): Unit =
    if (LogInfo) log.info(message)

  def logInfo(template: String, arg1: Any): Unit =
    if (LogInfo) log.info(template, arg1)

  def logInfo(template: String, arg1: Any, arg2: Any): Unit =
    if (LogInfo) log.info(template, arg1, arg2)

  override def preStart(): Unit = {
    super.preStart()
    require(!cluster.isTerminated, "Cluster node must not be terminated")

    // subscribe to cluster changes, re-subscribe when restart
    cluster.subscribe(self, classOf[MemberExited], classOf[MemberRemoved])

    setTimer(CleanupTimer, Cleanup, 1.minute, repeat = true)

    // defer subscription to avoid some jitter when
    // starting/joining several nodes at the same time
    cluster.registerOnMemberUp(self ! StartOldestChangedBuffer)
  }

  override def postStop(): Unit = {
    cancelTimer(CleanupTimer)
    cluster.unsubscribe(self)
    super.postStop()
  }

  def peer(at: Address): ActorSelection = context.actorSelection(self.path.toStringWithAddress(at))

  def getNextOldestChanged(): Unit =
    if (oldestChangedReceived) {
      oldestChangedReceived = false
      oldestChangedBuffer ! GetNext
    }

  startWith(Start, Uninitialized)

  when(Start) {
    case Event(StartOldestChangedBuffer, _) ⇒
      oldestChangedBuffer = context.actorOf(Props(classOf[OldestChangedBuffer], role).
        withDispatcher(context.props.dispatcher))
      getNextOldestChanged()
      stay

    case Event(InitialOldestState(oldestOption, safeToBeOldest), _) ⇒
      oldestChangedReceived = true
      if (oldestOption == selfAddressOption && safeToBeOldest)
        // oldest immediately
        gotoOldest()
      else if (oldestOption == selfAddressOption)
        goto(BecomingOldest) using BecomingOldestData(None)
      else
        goto(Younger) using YoungerData(oldestOption)
  }

  when(Younger) {
    case Event(OldestChanged(oldestOption), YoungerData(previousOldestOption)) ⇒
      oldestChangedReceived = true
      if (oldestOption == selfAddressOption) {
        logInfo("Younger observed OldestChanged: [{} -> myself]", previousOldestOption)
        previousOldestOption match {
          case None                                 ⇒ gotoOldest()
          case Some(prev) if removed.contains(prev) ⇒ gotoOldest()
          case Some(prev) ⇒
            peer(prev) ! HandOverToMe
            goto(BecomingOldest) using BecomingOldestData(previousOldestOption)
        }
      } else {
        logInfo("Younger observed OldestChanged: [{} -> {}]", previousOldestOption, oldestOption)
        getNextOldestChanged()
        stay using YoungerData(oldestOption)
      }

    case Event(MemberRemoved(m, _), _) if m.address == cluster.selfAddress ⇒
      logInfo("Self removed, stopping ClusterSingletonManager")
      stop()

    case Event(MemberRemoved(m, _), _) ⇒
      scheduleDelayedMemberRemoved(m)
      stay

    case Event(DelayedMemberRemoved(m), YoungerData(Some(previousOldest))) if m.address == previousOldest ⇒
      logInfo("Previous oldest removed [{}]", m.address)
      addRemoved(m.address)
      // transition when OldestChanged
      stay using YoungerData(None)
  }

  when(BecomingOldest) {

    case Event(HandOverInProgress, _) ⇒
      // confirmation that the hand-over process has started
      logInfo("Hand-over in progress at [{}]", sender().path.address)
      cancelTimer(HandOverRetryTimer)
      stay

    case Event(HandOverDone, BecomingOldestData(Some(previousOldest))) ⇒
      if (sender().path.address == previousOldest)
        gotoOldest()
      else {
        logInfo("Ignoring HandOverDone in BecomingOldest from [{}]. Expected previous oldest [{}]",
          sender().path.address, previousOldest)
        stay
      }

    case Event(MemberRemoved(m, _), _) if m.address == cluster.selfAddress ⇒
      logInfo("Self removed, stopping ClusterSingletonManager")
      stop()

    case Event(MemberRemoved(m, _), _) ⇒
      scheduleDelayedMemberRemoved(m)
      stay

    case Event(DelayedMemberRemoved(m), BecomingOldestData(Some(previousOldest))) if m.address == previousOldest ⇒
      logInfo("Previous oldest [{}] removed", previousOldest)
      addRemoved(m.address)
      gotoOldest()

    case Event(TakeOverFromMe, BecomingOldestData(None)) ⇒
      sender() ! HandOverToMe
      stay using BecomingOldestData(Some(sender().path.address))

    case Event(TakeOverFromMe, BecomingOldestData(Some(previousOldest))) ⇒
      if (previousOldest == sender().path.address) sender() ! HandOverToMe
      else logInfo("Ignoring TakeOver request in BecomingOldest from [{}]. Expected previous oldest [{}]",
        sender().path.address, previousOldest)
      stay

    case Event(HandOverRetry(count), BecomingOldestData(previousOldestOption)) ⇒
      if (count <= maxHandOverRetries) {
        logInfo("Retry [{}], sending HandOverToMe to [{}]", count, previousOldestOption)
        previousOldestOption foreach { peer(_) ! HandOverToMe }
        setTimer(HandOverRetryTimer, HandOverRetry(count + 1), handOverRetryInterval, repeat = false)
        stay()
      } else if (previousOldestOption forall removed.contains) {
        // can't send HandOverToMe, previousOldest unknown for new node (or restart)
        // previous oldest might be down or removed, so no TakeOverFromMe message is received
        logInfo("Timeout in BecomingOldest. Previous oldest unknown, removed and no TakeOver request.")
        gotoOldest()
      } else if (cluster.isTerminated)
        stop()
      else
        throw new ClusterSingletonManagerIsStuck(
          s"Becoming singleton oldest was stuck because previous oldest [${previousOldestOption}] is unresponsive")
  }

  def scheduleDelayedMemberRemoved(m: Member): Unit = {
    if (removalMargin > Duration.Zero) {
      log.debug("Schedule DelayedMemberRemoved for [{}]", m.address)
      context.system.scheduler.scheduleOnce(removalMargin, self, DelayedMemberRemoved(m))(context.dispatcher)
    } else
      self ! DelayedMemberRemoved(m)
  }

  def gotoOldest(): State = {
    val singleton = context watch context.actorOf(singletonProps, singletonName)
    logInfo("Singleton manager starting singleton actor [{}]", singleton.path)
    goto(Oldest) using OldestData(singleton)
  }

  when(Oldest) {
    case Event(OldestChanged(oldestOption), OldestData(singleton, singletonTerminated)) ⇒
      oldestChangedReceived = true
      logInfo("Oldest observed OldestChanged: [{} -> {}]", cluster.selfAddress, oldestOption)
      oldestOption match {
        case Some(a) if a == cluster.selfAddress ⇒
          // already oldest
          stay
        case Some(a) if !selfExited && removed.contains(a) ⇒
          gotoHandingOver(singleton, singletonTerminated, None)
        case Some(a) ⇒
          // send TakeOver request in case the new oldest doesn't know previous oldest
          peer(a) ! TakeOverFromMe
          setTimer(TakeOverRetryTimer, TakeOverRetry(1), handOverRetryInterval, repeat = false)
          goto(WasOldest) using WasOldestData(singleton, singletonTerminated, newOldestOption = Some(a))
        case None ⇒
          // new oldest will initiate the hand-over
          setTimer(TakeOverRetryTimer, TakeOverRetry(1), handOverRetryInterval, repeat = false)
          goto(WasOldest) using WasOldestData(singleton, singletonTerminated, newOldestOption = None)
      }

    case Event(HandOverToMe, OldestData(singleton, singletonTerminated)) ⇒
      gotoHandingOver(singleton, singletonTerminated, Some(sender()))

    case Event(Terminated(ref), d @ OldestData(singleton, _)) if ref == singleton ⇒
      stay using d.copy(singletonTerminated = true)
  }

  when(WasOldest) {
    case Event(TakeOverRetry(count), WasOldestData(_, _, newOldestOption)) ⇒
      if (count <= maxTakeOverRetries) {
        logInfo("Retry [{}], sending TakeOverFromMe to [{}]", count, newOldestOption)
        newOldestOption foreach { peer(_) ! TakeOverFromMe }
        setTimer(TakeOverRetryTimer, TakeOverRetry(count + 1), handOverRetryInterval, repeat = false)
        stay
      } else if (cluster.isTerminated)
        stop()
      else
        throw new ClusterSingletonManagerIsStuck(s"Expected hand-over to [${newOldestOption}] never occured")

    case Event(HandOverToMe, WasOldestData(singleton, singletonTerminated, _)) ⇒
      gotoHandingOver(singleton, singletonTerminated, Some(sender()))

    case Event(MemberRemoved(m, _), _) if m.address == cluster.selfAddress && !selfExited ⇒
      logInfo("Self removed, stopping ClusterSingletonManager")
      stop()

    case Event(MemberRemoved(m, _), WasOldestData(singleton, singletonTerminated, Some(newOldest))) if !selfExited && m.address == newOldest ⇒
      addRemoved(m.address)
      gotoHandingOver(singleton, singletonTerminated, None)

    case Event(Terminated(ref), d @ WasOldestData(singleton, _, _)) if ref == singleton ⇒
      stay using d.copy(singletonTerminated = true)

  }

  def gotoHandingOver(singleton: ActorRef, singletonTerminated: Boolean, handOverTo: Option[ActorRef]): State = {
    if (singletonTerminated) {
      handOverDone(handOverTo)
    } else {
      handOverTo foreach { _ ! HandOverInProgress }
      singleton ! terminationMessage
      goto(HandingOver) using HandingOverData(singleton, handOverTo)
    }
  }

  when(HandingOver) {
    case (Event(Terminated(ref), HandingOverData(singleton, handOverTo))) if ref == singleton ⇒
      handOverDone(handOverTo)

    case Event(HandOverToMe, d @ HandingOverData(singleton, handOverTo)) if handOverTo == Some(sender()) ⇒
      // retry
      sender() ! HandOverInProgress
      stay

  }

  def handOverDone(handOverTo: Option[ActorRef]): State = {
    val newOldest = handOverTo.map(_.path.address)
    logInfo("Singleton terminated, hand-over done [{} -> {}]", cluster.selfAddress, newOldest)
    handOverTo foreach { _ ! HandOverDone }
    if (removed.contains(cluster.selfAddress)) {
      logInfo("Self removed, stopping ClusterSingletonManager")
      stop()
    } else if (selfExited)
      goto(End) using EndData
    else
      goto(Younger) using YoungerData(newOldest)
  }

  when(End) {
    case Event(MemberRemoved(m, _), _) if m.address == cluster.selfAddress ⇒
      logInfo("Self removed, stopping ClusterSingletonManager")
      stop()
  }

  whenUnhandled {
    case Event(_: CurrentClusterState, _) ⇒ stay
    case Event(MemberExited(m), _) ⇒
      if (m.address == cluster.selfAddress) {
        selfExited = true
        logInfo("Exited [{}]", m.address)
      }
      stay
    case Event(MemberRemoved(m, _), _) if m.address == cluster.selfAddress && !selfExited ⇒
      logInfo("Self removed, stopping ClusterSingletonManager")
      stop()
    case Event(MemberRemoved(m, _), _) ⇒
      if (!selfExited) logInfo("Member removed [{}]", m.address)
      addRemoved(m.address)
      stay
    case Event(DelayedMemberRemoved(m), _) ⇒
      if (!selfExited) logInfo("Member removed [{}]", m.address)
      addRemoved(m.address)
      stay
    case Event(TakeOverFromMe, _) ⇒
      logInfo("Ignoring TakeOver request in [{}] from [{}].", stateName, sender().path.address)
      stay
    case Event(Cleanup, _) ⇒
      cleanupOverdueNotMemberAnyMore()
      stay
  }

  onTransition {
    case from -> to ⇒ logInfo("ClusterSingletonManager state change [{} -> {}]", from, to)
  }

  onTransition {
    case _ -> BecomingOldest ⇒ setTimer(HandOverRetryTimer, HandOverRetry(1), handOverRetryInterval, repeat = false)
  }

  onTransition {
    case BecomingOldest -> _ ⇒ cancelTimer(HandOverRetryTimer)
    case WasOldest -> _      ⇒ cancelTimer(TakeOverRetryTimer)
  }

  onTransition {
    case _ -> (Younger | Oldest) ⇒ getNextOldestChanged()
  }

  onTransition {
    case _ -> (Younger | End) if removed.contains(cluster.selfAddress) ⇒
      logInfo("Self removed, stopping ClusterSingletonManager")
      // note that FSM.stop() can't be used in onTransition
      context.stop(self)
  }

}
