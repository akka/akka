/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.contrib.pattern

import scala.concurrent.duration._
import scala.collection.immutable
import akka.actor.Actor
import akka.actor.Actor.Receive
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSelection
import akka.actor.Address
import akka.actor.FSM
import akka.actor.Props
import akka.actor.Terminated
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.cluster.Member
import akka.cluster.MemberStatus
import akka.AkkaException

object ClusterSingletonManager {

  /**
   * Scala API: Factory method for `ClusterSingletonManager` [[akka.actor.Props]].
   */
  def props(
    singletonProps: Option[Any] ⇒ Props,
    singletonName: String,
    terminationMessage: Any,
    role: Option[String],
    maxHandOverRetries: Int = 20,
    maxTakeOverRetries: Int = 15,
    retryInterval: FiniteDuration = 1.second): Props =
    Props(classOf[ClusterSingletonManager], singletonProps, singletonName, terminationMessage, role,
      maxHandOverRetries, maxTakeOverRetries, retryInterval)

  /**
   * Java API: Factory method for `ClusterSingletonManager` [[akka.actor.Props]].
   */
  def props(
    singletonName: String,
    terminationMessage: Any,
    role: String,
    maxHandOverRetries: Int,
    maxTakeOverRetries: Int,
    retryInterval: FiniteDuration,
    singletonPropsFactory: ClusterSingletonPropsFactory): Props =
    props(handOverData ⇒ singletonPropsFactory.create(handOverData.orNull), singletonName, terminationMessage,
      ClusterSingletonManager.Internal.roleOption(role), maxHandOverRetries, maxTakeOverRetries, retryInterval)

  /**
   * Java API: Factory method for `ClusterSingletonManager` [[akka.actor.Props]]
   * with default values.
   */
  def defaultProps(
    singletonName: String,
    terminationMessage: Any,
    role: String,
    singletonPropsFactory: ClusterSingletonPropsFactory): Props =
    props(handOverData ⇒ singletonPropsFactory.create(handOverData.orNull), singletonName, terminationMessage,
      ClusterSingletonManager.Internal.roleOption(role))

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
  private object Internal {
    /**
     * Sent from new oldest to previous oldest to initate the
     * hand-over process. `HandOverInProgress` and `HandOverDone`
     * are expected replies.
     */
    case object HandOverToMe
    /**
     * Confirmation by the previous oldest that the hand
     * over process, shut down of the singleton actor, has
     * started.
     */
    case object HandOverInProgress
    /**
     * Confirmation by the previous oldest that the singleton
     * actor has been terminated and the hand-over process is
     * completed. The `handOverData` holds the message, if any,
     * sent from the singleton actor to its parent ClusterSingletonManager
     * when shutting down. It is passed to the `singletonProps`
     * factory on the new oldest node.
     */
    case class HandOverDone(handOverData: Option[Any])
    /**
     * Sent from from previous oldest to new oldest to
     * initiate the normal hand-over process.
     * Especially useful when new node joins and becomes
     * oldest immediately, without knowing who was previous
     * oldest.
     */
    case object TakeOverFromMe

    case class HandOverRetry(count: Int)
    case class TakeOverRetry(count: Int)
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
    case class YoungerData(oldestOption: Option[Address]) extends Data
    case class BecomingOldestData(previousOldestOption: Option[Address]) extends Data
    case class OldestData(singleton: ActorRef, singletonTerminated: Boolean = false,
                          handOverData: Option[Any] = None) extends Data
    case class WasOldestData(singleton: ActorRef, singletonTerminated: Boolean, handOverData: Option[Any],
                             newOldestOption: Option[Address]) extends Data
    case class HandingOverData(singleton: ActorRef, handOverTo: Option[ActorRef], handOverData: Option[Any]) extends Data
    case object EndData extends Data

    val HandOverRetryTimer = "hand-over-retry"
    val TakeOverRetryTimer = "take-over-retry"
    val CleanupTimer = "cleanup"

    def roleOption(role: String): Option[String] = role match {
      case null | "" ⇒ None
      case _         ⇒ Some(role)
    }

    object OldestChangedBuffer {
      /**
       * Request to deliver one more event.
       */
      case object GetNext
      /**
       * The first event, corresponding to CurrentClusterState.
       */
      case class InitialOldestState(oldest: Option[Address], memberCount: Int)

      case class OldestChanged(oldest: Option[Address])
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
      import context.dispatcher

      val cluster = Cluster(context.system)
      // sort by age, oldest first
      val ageOrdering = Ordering.fromLessThan[Member] { (a, b) ⇒ a.isOlderThan(b) }
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
        membersByAge = immutable.SortedSet.empty(ageOrdering) ++ state.members.collect {
          case m if m.status == MemberStatus.Up && matchingRole(m) ⇒ m
        }
        val initial = InitialOldestState(membersByAge.headOption.map(_.address), membersByAge.size)
        changes :+= initial
      }

      def add(m: Member): Unit = {
        if (matchingRole(m))
          trackChange { () ⇒ membersByAge += m }
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
 * Java API. Factory for the [[akka.actor.Props]] of the singleton
 * actor instance. Used in constructor of
 * [[akka.contrib.pattern.ClusterSingletonManager]]
 */
@SerialVersionUID(1L)
trait ClusterSingletonPropsFactory extends Serializable {
  /**
   * Create the `Props` from the `handOverData` sent from
   * previous singleton. `handOverData` might be null
   * when no hand-over took place, or when the there is no need
   * for sending data to the new singleton.
   */
  def create(handOverData: Any): Props
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
 * The singleton actor is always running on the oldest member, which can
 * be determined by [[akka.cluster.Member#isOlderThan]].
 * This can change when removing members. A graceful hand over can normally
 * be performed when current oldest node is leaving the cluster. Be aware that
 * there is a short time period when there is no active singleton during the
 * hand-over process.
 *
 * The singleton actor can at any time send a message to its parent
 * ClusterSingletonManager and this message will be passed to the
 * `singletonProps` factory on the new oldest node when a graceful
 * hand-over is performed.
 *
 * The cluster failure detector will notice when oldest node
 * becomes unreachable due to things like JVM crash, hard shut down,
 * or network failure. Then a new oldest node will take over and a
 * new singleton actor is created. For these failure scenarios there
 * will not be a graceful hand-over, but more than one active singletons
 * is prevented by all reasonable means. Some corner cases are eventually
 * resolved by configurable timeouts.
 *
 * You access the singleton actor with `actorSelection` using the names you have
 * specified when creating the ClusterSingletonManager. You can subscribe to
 * [[akka.cluster.ClusterEvent.MemberEvent]] and sort the members by age
 * ([[akka.cluster.ClusterEvent.Member#isOlderThan]]) to keep track of oldest member.
 * Alternatively the singleton actor may broadcast its existence when it is started.
 *
 * Use factory method [[ClusterSingletonManager#props] to create the
 * [[akka.actor.Props]] for the actor.
 *
 * ==Arguments==
 *
 * '''''singletonProps''''' Factory for [[akka.actor.Props]] of the
 *   singleton actor instance. The `Option` parameter is the the
 *   `handOverData` sent from previous singleton. `handOverData`
 *    might be None when no hand-over took place, or when the there
 *    is no need for sending data to the new singleton. The `handOverData`
 *    is typically passed as parameter to the constructor of the
 *    singleton actor.
 *
 * '''''singletonName''''' The actor name of the child singleton actor.
 *
 * '''''terminationMessage''''' When handing over to a new oldest node
 *   this `terminationMessage` is sent to the singleton actor to tell
 *   it to finish its work, close resources, and stop. It can sending
 *   a message back to the parent ClusterSingletonManager, which will
 *   passed to the `singletonProps` factory on the new oldest node.
 *   The hand-over to the new oldest node is completed when the
 *   singleton actor is terminated.
 *   Note that [[akka.actor.PoisonPill]] is a perfectly fine
 *   `terminationMessage` if you only need to stop the actor.
 *
 * '''''role''''' Singleton among the nodes tagged with specified role.
 *   If the role is not specified it's a singleton among all nodes in
 *   the cluster.
 *
 * '''''maxHandOverRetries''''' When a node is becoming oldest it sends
 *   hand-over request to previous oldest. This is retried with the
 *   `retryInterval` until the previous oldest confirms that the hand
 *   over has started, or this `maxHandOverRetries` limit has been
 *   reached. If the retry limit is reached it takes the decision to be
 *   the new oldest if previous oldest is unknown (typically removed),
 *   otherwise it initiates a new round by throwing
 *   [[akka.contrib.pattern.ClusterSingletonManagerIsStuck]] and expecting
 *   restart with fresh state. For a cluster with many members you might
 *   need to increase this retry limit because it takes longer time to
 *   propagate changes across all nodes.
 *
 * '''''maxTakeOverRetries''''' When a oldest node is not oldest any more
 *   it sends take over request to the new oldest to initiate the normal
 *   hand-over process. This is especially useful when new node joins and becomes
 *   oldest immediately, without knowing who was previous oldest. This is retried
 *   with the `retryInterval` until this retry limit has been reached. If the retry
 *   limit is reached it initiates a new round by throwing
 *   [[akka.contrib.pattern.ClusterSingletonManagerIsStuck]] and expecting
 *   restart with fresh state. This will also cause the singleton actor to be
 *   stopped. `maxTakeOverRetries` must be less than `maxHandOverRetries` to
 *   ensure that new oldest doesn't start singleton actor before previous is
 *   stopped for certain corner cases.
 */
class ClusterSingletonManager(
  singletonProps: Option[Any] ⇒ Props,
  singletonName: String,
  terminationMessage: Any,
  role: Option[String],
  maxHandOverRetries: Int,
  maxTakeOverRetries: Int,
  retryInterval: FiniteDuration)
  extends Actor with FSM[ClusterSingletonManager.State, ClusterSingletonManager.Data] {

  // to ensure that new oldest doesn't start singleton actor before previous is stopped for certain corner cases
  require(maxTakeOverRetries < maxHandOverRetries,
    s"maxTakeOverRetries [${maxTakeOverRetries}]must be < maxHandOverRetries [${maxHandOverRetries}]")

  import ClusterSingletonManager._
  import ClusterSingletonManager.Internal._
  import ClusterSingletonManager.Internal.OldestChangedBuffer._

  val cluster = Cluster(context.system)
  val selfAddressOption = Some(cluster.selfAddress)
  import cluster.settings.LogInfo

  require(role.forall(cluster.selfRoles.contains),
    s"This cluster member [${cluster.selfAddress}] doesn't have the role [$role]")

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
    cluster.subscribe(self, classOf[MemberExited])
    cluster.subscribe(self, classOf[MemberRemoved])

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

    case Event(InitialOldestState(oldestOption, memberCount), _) ⇒
      oldestChangedReceived = true
      if (oldestOption == selfAddressOption && memberCount == 1)
        // alone, oldest immediately
        gotoOldest(None)
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
          case None                                 ⇒ gotoOldest(None)
          case Some(prev) if removed.contains(prev) ⇒ gotoOldest(None)
          case Some(prev) ⇒
            peer(prev) ! HandOverToMe
            goto(BecomingOldest) using BecomingOldestData(previousOldestOption)
        }
      } else {
        logInfo("Younger observed OldestChanged: [{} -> {}]", previousOldestOption, oldestOption)
        getNextOldestChanged()
        stay using YoungerData(oldestOption)
      }

    case Event(MemberRemoved(m), YoungerData(Some(previousOldest))) if m.address == previousOldest ⇒
      logInfo("Previous oldest removed [{}]", m.address)
      addRemoved(m.address)
      // transition when OldestChanged
      stay using YoungerData(None)

    case Event(MemberRemoved(m), _) if m.address == cluster.selfAddress ⇒
      logInfo("Self removed, stopping ClusterSingletonManager")
      stop()

  }

  when(BecomingOldest) {

    case Event(HandOverInProgress, _) ⇒
      // confirmation that the hand-over process has started
      logInfo("Hand-over in progress at [{}]", sender.path.address)
      cancelTimer(HandOverRetryTimer)
      stay

    case Event(HandOverDone(handOverData), BecomingOldestData(Some(previousOldest))) ⇒
      if (sender.path.address == previousOldest)
        gotoOldest(handOverData)
      else {
        logInfo("Ignoring HandOverDone in BecomingOldest from [{}]. Expected previous oldest [{}]",
          sender.path.address, previousOldest)
        stay
      }

    case Event(MemberRemoved(m), BecomingOldestData(Some(previousOldest))) if m.address == previousOldest ⇒
      logInfo("Previous oldest [{}] removed", previousOldest)
      addRemoved(m.address)
      stay

    case Event(TakeOverFromMe, BecomingOldestData(None)) ⇒
      sender ! HandOverToMe
      stay using BecomingOldestData(Some(sender.path.address))

    case Event(TakeOverFromMe, BecomingOldestData(Some(previousOldest))) ⇒
      if (previousOldest == sender.path.address) sender ! HandOverToMe
      else logInfo("Ignoring TakeOver request in BecomingOldest from [{}]. Expected previous oldest [{}]",
        sender.path.address, previousOldest)
      stay

    case Event(HandOverRetry(count), BecomingOldestData(previousOldestOption)) ⇒
      if (count <= maxHandOverRetries) {
        logInfo("Retry [{}], sending HandOverToMe to [{}]", count, previousOldestOption)
        previousOldestOption foreach { peer(_) ! HandOverToMe }
        setTimer(HandOverRetryTimer, HandOverRetry(count + 1), retryInterval, repeat = false)
        stay()
      } else if (previousOldestOption forall removed.contains) {
        // can't send HandOverToMe, previousOldest unknown for new node (or restart)
        // previous oldest might be down or removed, so no TakeOverFromMe message is received
        logInfo("Timeout in BecomingOldest. Previous oldest unknown, removed and no TakeOver request.")
        gotoOldest(None)
      } else
        throw new ClusterSingletonManagerIsStuck(
          s"Becoming singleton oldest was stuck because previous oldest [${previousOldestOption}] is unresponsive")

  }

  def gotoOldest(handOverData: Option[Any]): State = {
    logInfo("Singleton manager [{}] starting singleton actor", cluster.selfAddress)
    val singleton = context watch context.actorOf(singletonProps(handOverData), singletonName)
    goto(Oldest) using OldestData(singleton)
  }

  when(Oldest) {
    case Event(OldestChanged(oldestOption), OldestData(singleton, singletonTerminated, handOverData)) ⇒
      oldestChangedReceived = true
      logInfo("Oldest observed OldestChanged: [{} -> {}]", cluster.selfAddress, oldestOption)
      oldestOption match {
        case Some(a) if a == cluster.selfAddress ⇒
          // already oldest
          stay
        case Some(a) if removed.contains(a) ⇒
          gotoHandingOver(singleton, singletonTerminated, handOverData, None)
        case Some(a) ⇒
          // send TakeOver request in case the new oldest doesn't know previous oldest
          peer(a) ! TakeOverFromMe
          setTimer(TakeOverRetryTimer, TakeOverRetry(1), retryInterval, repeat = false)
          goto(WasOldest) using WasOldestData(singleton, singletonTerminated, handOverData, newOldestOption = Some(a))
        case None ⇒
          // new oldest will initiate the hand-over
          setTimer(TakeOverRetryTimer, TakeOverRetry(1), retryInterval, repeat = false)
          goto(WasOldest) using WasOldestData(singleton, singletonTerminated, handOverData, newOldestOption = None)
      }

    case Event(HandOverToMe, OldestData(singleton, singletonTerminated, handOverData)) ⇒
      gotoHandingOver(singleton, singletonTerminated, handOverData, Some(sender))

    case Event(singletonHandOverMessage, d @ OldestData(singleton, _, _)) if sender == singleton ⇒
      stay using d.copy(handOverData = Some(singletonHandOverMessage))

    case Event(Terminated(ref), d @ OldestData(singleton, _, _)) if ref == singleton ⇒
      stay using d.copy(singletonTerminated = true)
  }

  when(WasOldest) {
    case Event(TakeOverRetry(count), WasOldestData(_, _, _, newOldestOption)) ⇒
      if (count <= maxTakeOverRetries) {
        logInfo("Retry [{}], sending TakeOverFromMe to [{}]", count, newOldestOption)
        newOldestOption foreach { peer(_) ! TakeOverFromMe }
        setTimer(TakeOverRetryTimer, TakeOverRetry(count + 1), retryInterval, repeat = false)
        stay
      } else
        throw new ClusterSingletonManagerIsStuck(s"Expected hand-over to [${newOldestOption}] never occured")

    case Event(HandOverToMe, WasOldestData(singleton, singletonTerminated, handOverData, _)) ⇒
      gotoHandingOver(singleton, singletonTerminated, handOverData, Some(sender))

    case Event(MemberRemoved(m), WasOldestData(singleton, singletonTerminated, handOverData, Some(newOldest))) if !selfExited && m.address == newOldest ⇒
      addRemoved(m.address)
      gotoHandingOver(singleton, singletonTerminated, handOverData, None)

    case Event(singletonHandOverMessage, d @ WasOldestData(singleton, _, _, _)) if sender == singleton ⇒
      stay using d.copy(handOverData = Some(singletonHandOverMessage))

    case Event(Terminated(ref), d @ WasOldestData(singleton, _, _, _)) if ref == singleton ⇒
      stay using d.copy(singletonTerminated = true)

  }

  def gotoHandingOver(singleton: ActorRef, singletonTerminated: Boolean, handOverData: Option[Any], handOverTo: Option[ActorRef]): State = {
    if (singletonTerminated) {
      handOverDone(handOverTo, handOverData)
    } else {
      handOverTo foreach { _ ! HandOverInProgress }
      singleton ! terminationMessage
      goto(HandingOver) using HandingOverData(singleton, handOverTo, handOverData)
    }
  }

  when(HandingOver) {
    case (Event(Terminated(ref), HandingOverData(singleton, handOverTo, handOverData))) if ref == singleton ⇒
      handOverDone(handOverTo, handOverData)

    case Event(HandOverToMe, d @ HandingOverData(singleton, handOverTo, _)) if handOverTo == Some(sender) ⇒
      // retry
      sender ! HandOverInProgress
      stay

    case Event(singletonHandOverMessage, d @ HandingOverData(singleton, _, _)) if sender == singleton ⇒
      stay using d.copy(handOverData = Some(singletonHandOverMessage))

  }

  def handOverDone(handOverTo: Option[ActorRef], handOverData: Option[Any]): State = {
    val newOldest = handOverTo.map(_.path.address)
    logInfo("Singleton terminated, hand-over done [{} -> {}]", cluster.selfAddress, newOldest)
    handOverTo foreach { _ ! HandOverDone(handOverData) }
    if (selfExited || removed.contains(cluster.selfAddress))
      goto(End) using EndData
    else
      goto(Younger) using YoungerData(newOldest)
  }

  when(End) {
    case Event(MemberRemoved(m), _) if m.address == cluster.selfAddress ⇒
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
    case Event(MemberRemoved(m), _) ⇒
      if (!selfExited) logInfo("Member removed [{}]", m.address)
      addRemoved(m.address)
      stay
    case Event(TakeOverFromMe, _) ⇒
      logInfo("Ignoring TakeOver request in [{}] from [{}].", stateName, sender.path.address)
      stay
    case Event(Cleanup, _) ⇒
      cleanupOverdueNotMemberAnyMore()
      stay
  }

  onTransition {
    case from -> to ⇒ logInfo("ClusterSingletonManager state change [{} -> {}]", from, to)
  }

  onTransition {
    case _ -> BecomingOldest ⇒ setTimer(HandOverRetryTimer, HandOverRetry(1), retryInterval, repeat = false)
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