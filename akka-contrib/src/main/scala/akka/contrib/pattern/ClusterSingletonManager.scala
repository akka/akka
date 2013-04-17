/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.contrib.pattern

import scala.concurrent.duration._
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
    retryInterval: FiniteDuration = 1.second,
    loggingEnabled: Boolean = true): Props =
    Props(classOf[ClusterSingletonManager], singletonProps, singletonName, terminationMessage, role,
      maxHandOverRetries, maxTakeOverRetries, retryInterval, loggingEnabled)

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
    loggingEnabled: Boolean,
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
     * Sent from new leader to previous leader to initate the
     * hand-over process. `HandOverInProgress` and `HandOverDone`
     * are expected replies.
     */
    case object HandOverToMe
    /**
     * Confirmation by the previous leader that the hand
     * over process, shut down of the singleton actor, has
     * started.
     */
    case object HandOverInProgress
    /**
     * Confirmation by the previous leader that the singleton
     * actor has been terminated and the hand-over process is
     * completed. The `handOverData` holds the message, if any,
     * sent from the singleton actor to its parent ClusterSingletonManager
     * when shutting down. It is passed to the `singletonProps`
     * factory on the new leader node.
     */
    case class HandOverDone(handOverData: Option[Any])
    /**
     * Sent from from previous leader to new leader to
     * initiate the normal hand-over process.
     * Especially useful when new node joins and becomes
     * leader immediately, without knowing who was previous
     * leader.
     */
    case object TakeOverFromMe

    case class HandOverRetry(count: Int)
    case class TakeOverRetry(count: Int)
    case object Cleanup
    case object StartLeaderChangedBuffer

    case object Start extends State
    case object Leader extends State
    case object NonLeader extends State
    case object BecomingLeader extends State
    case object WasLeader extends State
    case object HandingOver extends State
    case object TakeOver extends State

    case object Uninitialized extends Data
    case class NonLeaderData(leaderOption: Option[Address]) extends Data
    case class BecomingLeaderData(previousLeaderOption: Option[Address]) extends Data
    case class LeaderData(singleton: ActorRef, singletonTerminated: Boolean = false,
                          handOverData: Option[Any] = None) extends Data
    case class WasLeaderData(singleton: ActorRef, singletonTerminated: Boolean, handOverData: Option[Any],
                             newLeaderOption: Option[Address]) extends Data
    case class HandingOverData(singleton: ActorRef, handOverTo: Option[ActorRef], handOverData: Option[Any]) extends Data

    val HandOverRetryTimer = "hand-over-retry"
    val TakeOverRetryTimer = "take-over-retry"
    val CleanupTimer = "cleanup"

    def roleOption(role: String): Option[String] = role match {
      case null | "" ⇒ None
      case _         ⇒ Some(role)
    }

    object LeaderChangedBuffer {
      /**
       * Request to deliver one more event.
       */
      case object GetNext
      /**
       * The first event, corresponding to CurrentClusterState.
       */
      case class InitialLeaderState(leader: Option[Address], memberCount: Int)
    }

    /**
     * Notifications of [[akka.cluster.ClusterEvent.LeaderChanged]] is tunneled
     * via this actor (child of ClusterSingletonManager) to be able to deliver
     * one change at a time. Avoiding simultaneous leader changes simplifies
     * the process in ClusterSingletonManager. ClusterSingletonManager requests
     * next event with `GetNext` when it is ready for it. Only one outstanding
     * `GetNext` request is allowed. Incoming events are buffered and delivered
     * upon `GetNext` request.
     */
    class LeaderChangedBuffer(role: Option[String]) extends Actor {
      import LeaderChangedBuffer._
      import context.dispatcher

      val cluster = Cluster(context.system)
      var changes = Vector.empty[AnyRef]
      var memberCount = 0

      // subscribe to LeaderChanged, re-subscribe when restart
      override def preStart(): Unit = role match {
        case None    ⇒ cluster.subscribe(self, classOf[LeaderChanged])
        case Some(_) ⇒ cluster.subscribe(self, classOf[RoleLeaderChanged])
      }
      override def postStop(): Unit = cluster.unsubscribe(self)

      def receive = {
        case state: CurrentClusterState ⇒
          val initial = role match {
            case None    ⇒ InitialLeaderState(state.leader, state.members.size)
            case Some(r) ⇒ InitialLeaderState(state.roleLeader(r), state.members.count(_.hasRole(r)))
          }
          changes :+= initial
        case event: LeaderChanged ⇒
          changes :+= event
        case RoleLeaderChanged(r, leader) ⇒
          if (role.orNull == r) changes :+= LeaderChanged(leader)
        case GetNext if changes.isEmpty ⇒
          context.become(deliverNext, discardOld = false)
        case GetNext ⇒
          val event = changes.head
          changes = changes.tail
          context.parent ! event
      }

      // the buffer was empty when GetNext was received, deliver next event immediately
      def deliverNext: Actor.Receive = {
        case state: CurrentClusterState ⇒
          val initial = role match {
            case None    ⇒ InitialLeaderState(state.leader, state.members.size)
            case Some(r) ⇒ InitialLeaderState(state.roleLeader(r), state.members.count(_.hasRole(r)))
          }
          context.parent ! initial
          context.unbecome()
        case event: LeaderChanged ⇒
          context.parent ! event
          context.unbecome()
        case RoleLeaderChanged(r, leader) ⇒
          if (role.orNull == r) {
            context.parent ! LeaderChanged(leader)
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
 * The actual singleton is started on the leader node by creating a child
 * actor from the supplied `singletonProps`.
 *
 * The singleton actor is always running on the leader member, which is
 * nothing more than the address currently sorted first in the member
 * ring. This can change when adding or removing members. A graceful hand
 * over can normally be performed when joining a new node that becomes
 * leader or removing current leader node. Be aware that there is a
 * short time period when there is no active singleton during the
 * hand-over process.
 *
 * The singleton actor can at any time send a message to its parent
 * ClusterSingletonManager and this message will be passed to the
 * `singletonProps` factory on the new leader node when a graceful
 * hand-over is performed.
 *
 * The cluster failure detector will notice when a leader node
 * becomes unreachable due to things like JVM crash, hard shut down,
 * or network failure. Then a new leader node will take over and a
 * new singleton actor is created. For these failure scenarios there
 * will not be a graceful hand-over, but more than one active singletons
 * is prevented by all reasonable means. Some corner cases are eventually
 * resolved by configurable timeouts.
 *
 * You access the singleton actor with `actorSelection` using the names you have
 * specified when creating the ClusterSingletonManager. You can subscribe to
 * [[akka.cluster.ClusterEvent.LeaderChanged]] or
 * [[akka.cluster.ClusterEvent.RoleLeaderChanged]] to keep track of which node
 * it is supposed to be running on. Alternatively the singleton actor may
 * broadcast its existence when it is started.
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
 * '''''terminationMessage''''' When handing over to a new leader node
 *   this `terminationMessage` is sent to the singleton actor to tell
 *   it to finish its work, close resources, and stop. It can sending
 *   a message back to the parent ClusterSingletonManager, which will
 *   passed to the `singletonProps` factory on the new leader node.
 *   The hand-over to the new leader node is completed when the
 *   singleton actor is terminated.
 *   Note that [[akka.actor.PoisonPill]] is a perfectly fine
 *   `terminationMessage` if you only need to stop the actor.
 *
 * '''''role''''' Singleton among the nodes tagged with specified role.
 *   If the role is not specified it's a singleton among all nodes in
 *   the cluster.
 *
 * '''''maxHandOverRetries''''' When a node is becoming leader it sends
 *   hand-over request to previous leader. This is retried with the
 *   `retryInterval` until the previous leader confirms that the hand
 *   over has started, or this `maxHandOverRetries` limit has been
 *   reached. If the retry limit is reached it takes the decision to be
 *   the new leader if previous leader is unknown (typically removed),
 *   otherwise it initiates a new round by throwing
 *   [[akka.contrib.pattern.ClusterSingletonManagerIsStuck]] and expecting
 *   restart with fresh state. For a cluster with many members you might
 *   need to increase this retry limit because it takes longer time to
 *   propagate changes across all nodes.
 *
 * '''''maxTakeOverRetries''''' When a leader node is not leader any more
 *   it sends take over request to the new leader to initiate the normal
 *   hand-over process. This is especially useful when new node joins and becomes
 *   leader immediately, without knowing who was previous leader. This is retried
 *   with the `retryInterval` until this retry limit has been reached. If the retry
 *   limit is reached it initiates a new round by throwing
 *   [[akka.contrib.pattern.ClusterSingletonManagerIsStuck]] and expecting
 *   restart with fresh state. This will also cause the singleton actor to be
 *   stopped. `maxTakeOverRetries` must be less than `maxHandOverRetries` to
 *   ensure that new leader doesn't start singleton actor before previous is
 *   stopped for certain corner cases.
 *
 * '''''loggingEnabled''''' Logging of what is going on at info log level.
 */
class ClusterSingletonManager(
  singletonProps: Option[Any] ⇒ Props,
  singletonName: String,
  terminationMessage: Any,
  role: Option[String],
  maxHandOverRetries: Int,
  maxTakeOverRetries: Int,
  retryInterval: FiniteDuration,
  loggingEnabled: Boolean)
  extends Actor with FSM[ClusterSingletonManager.State, ClusterSingletonManager.Data] {

  // to ensure that new leader doesn't start singleton actor before previous is stopped for certain corner cases
  require(maxTakeOverRetries < maxHandOverRetries,
    s"maxTakeOverRetries [${maxTakeOverRetries}]must be < maxHandOverRetries [${maxHandOverRetries}]")

  import ClusterSingletonManager._
  import ClusterSingletonManager.Internal._
  import ClusterSingletonManager.Internal.LeaderChangedBuffer._

  val cluster = Cluster(context.system)
  val selfAddressOption = Some(cluster.selfAddress)

  require(role.forall(cluster.selfRoles.contains),
    s"This cluster member [${cluster.selfAddress}] doesn't have the role [$role]")

  // started when when self member is Up
  var leaderChangedBuffer: ActorRef = _
  // Previous GetNext request delivered event and new GetNext is to be sent
  var leaderChangedReceived = true

  // keep track of previously removed members
  var removed = Map.empty[Address, Deadline]

  def addRemoved(address: Address): Unit =
    removed += address -> (Deadline.now + 15.minutes)

  def cleanupOverdueNotMemberAnyMore(): Unit = {
    removed = removed filter { case (address, deadline) ⇒ deadline.hasTimeLeft }
  }

  def logInfo(message: String): Unit =
    if (loggingEnabled) log.info(message)

  def logInfo(template: String, arg1: Any): Unit =
    if (loggingEnabled) log.info(template, arg1)

  def logInfo(template: String, arg1: Any, arg2: Any): Unit =
    if (loggingEnabled) log.info(template, arg1, arg2)

  override def preStart(): Unit = {
    super.preStart()
    require(!cluster.isTerminated, "Cluster node must not be terminated")

    // subscribe to cluster changes, re-subscribe when restart
    cluster.subscribe(self, classOf[MemberRemoved])

    setTimer(CleanupTimer, Cleanup, 1.minute, repeat = true)

    // defer subscription to LeaderChanged to avoid some jitter when
    // starting/joining several nodes at the same time
    cluster.registerOnMemberUp(self ! StartLeaderChangedBuffer)
  }

  override def postStop(): Unit = {
    cancelTimer(CleanupTimer)
    cluster.unsubscribe(self)
    super.postStop()
  }

  def peer(at: Address): ActorSelection = context.actorSelection(self.path.toStringWithAddress(at))

  def getNextLeaderChanged(): Unit =
    if (leaderChangedReceived) {
      leaderChangedReceived = false
      leaderChangedBuffer ! GetNext
    }

  startWith(Start, Uninitialized)

  when(Start) {
    case Event(StartLeaderChangedBuffer, _) ⇒
      leaderChangedBuffer = context.actorOf(Props(classOf[LeaderChangedBuffer], role).
        withDispatcher(context.props.dispatcher))
      getNextLeaderChanged()
      stay

    case Event(InitialLeaderState(leaderOption, memberCount), _) ⇒
      leaderChangedReceived = true
      if (leaderOption == selfAddressOption && memberCount == 1)
        // alone, leader immediately
        gotoLeader(None)
      else if (leaderOption == selfAddressOption)
        goto(BecomingLeader) using BecomingLeaderData(None)
      else
        goto(NonLeader) using NonLeaderData(leaderOption)
  }

  when(NonLeader) {
    case Event(LeaderChanged(leaderOption), NonLeaderData(previousLeaderOption)) ⇒
      leaderChangedReceived = true
      if (leaderOption == selfAddressOption) {
        logInfo("NonLeader observed LeaderChanged: [{} -> myself]", previousLeaderOption)
        previousLeaderOption match {
          case None                                 ⇒ gotoLeader(None)
          case Some(prev) if removed.contains(prev) ⇒ gotoLeader(None)
          case Some(prev) ⇒
            peer(prev) ! HandOverToMe
            goto(BecomingLeader) using BecomingLeaderData(previousLeaderOption)
        }
      } else {
        logInfo("NonLeader observed LeaderChanged: [{} -> {}]", previousLeaderOption, leaderOption)
        getNextLeaderChanged()
        stay using NonLeaderData(leaderOption)
      }

    case Event(MemberRemoved(m), NonLeaderData(Some(previousLeader))) if m.address == previousLeader ⇒
      logInfo("Previous leader removed [{}]", m.address)
      addRemoved(m.address)
      // transition when LeaderChanged
      stay using NonLeaderData(None)

    case Event(MemberRemoved(m), _) if m.address == cluster.selfAddress ⇒
      logInfo("Self removed, stopping ClusterSingletonManager")
      stop()

  }

  when(BecomingLeader) {

    case Event(HandOverInProgress, _) ⇒
      // confirmation that the hand-over process has started
      logInfo("Hand-over in progress at [{}]", sender.path.address)
      cancelTimer(HandOverRetryTimer)
      stay

    case Event(HandOverDone(handOverData), BecomingLeaderData(Some(previousLeader))) ⇒
      if (sender.path.address == previousLeader)
        gotoLeader(handOverData)
      else {
        logInfo("Ignoring HandOverDone in BecomingLeader from [{}]. Expected previous leader [{}]",
          sender.path.address, previousLeader)
        stay
      }

    case Event(MemberRemoved(m), BecomingLeaderData(Some(previousLeader))) if m.address == previousLeader ⇒
      logInfo("Previous leader [{}] removed", previousLeader)
      addRemoved(m.address)
      stay

    case Event(TakeOverFromMe, BecomingLeaderData(None)) ⇒
      sender ! HandOverToMe
      stay using BecomingLeaderData(Some(sender.path.address))

    case Event(TakeOverFromMe, BecomingLeaderData(Some(previousLeader))) ⇒
      if (previousLeader == sender.path.address) sender ! HandOverToMe
      else logInfo("Ignoring TakeOver request in BecomingLeader from [{}]. Expected previous leader [{}]",
        sender.path.address, previousLeader)
      stay

    case Event(HandOverRetry(count), BecomingLeaderData(previousLeaderOption)) ⇒
      if (count <= maxHandOverRetries) {
        logInfo("Retry [{}], sending HandOverToMe to [{}]", count, previousLeaderOption)
        previousLeaderOption foreach { peer(_) ! HandOverToMe }
        setTimer(HandOverRetryTimer, HandOverRetry(count + 1), retryInterval, repeat = false)
      } else if (previousLeaderOption forall removed.contains) {
        // can't send HandOverToMe, previousLeader unknown for new node (or restart)
        // previous leader might be down or removed, so no TakeOverFromMe message is received
        logInfo("Timeout in BecomingLeader. Previous leader unknown, removed and no TakeOver request.")
        gotoLeader(None)
      } else
        throw new ClusterSingletonManagerIsStuck(
          s"Becoming singleton leader was stuck because previous leader [${previousLeaderOption}] is unresponsive")

  }

  def gotoLeader(handOverData: Option[Any]): State = {
    logInfo("Singleton manager [{}] starting singleton actor", cluster.selfAddress)
    val singleton = context watch context.actorOf(singletonProps(handOverData), singletonName)
    goto(Leader) using LeaderData(singleton)
  }

  when(Leader) {
    case Event(LeaderChanged(leaderOption), LeaderData(singleton, singletonTerminated, handOverData)) ⇒
      leaderChangedReceived = true
      logInfo("Leader observed LeaderChanged: [{} -> {}]", cluster.selfAddress, leaderOption)
      leaderOption match {
        case Some(a) if a == cluster.selfAddress ⇒
          // already leader
          stay
        case Some(a) if removed.contains(a) ⇒
          gotoHandingOver(singleton, singletonTerminated, handOverData, None)
        case Some(a) ⇒
          // send TakeOver request in case the new leader doesn't know previous leader
          peer(a) ! TakeOverFromMe
          setTimer(TakeOverRetryTimer, TakeOverRetry(1), retryInterval, repeat = false)
          goto(WasLeader) using WasLeaderData(singleton, singletonTerminated, handOverData, newLeaderOption = Some(a))
        case None ⇒
          // new leader will initiate the hand-over
          setTimer(TakeOverRetryTimer, TakeOverRetry(1), retryInterval, repeat = false)
          goto(WasLeader) using WasLeaderData(singleton, singletonTerminated, handOverData, newLeaderOption = None)
      }

    case Event(HandOverToMe, LeaderData(singleton, singletonTerminated, handOverData)) ⇒
      gotoHandingOver(singleton, singletonTerminated, handOverData, Some(sender))

    case Event(singletonHandOverMessage, d @ LeaderData(singleton, _, _)) if sender == singleton ⇒
      stay using d.copy(handOverData = Some(singletonHandOverMessage))

    case Event(Terminated(ref), d @ LeaderData(singleton, _, _)) if ref == singleton ⇒
      stay using d.copy(singletonTerminated = true)
  }

  when(WasLeader) {
    case Event(TakeOverRetry(count), WasLeaderData(_, _, _, newLeaderOption)) ⇒
      if (count <= maxTakeOverRetries) {
        logInfo("Retry [{}], sending TakeOverFromMe to [{}]", count, newLeaderOption)
        newLeaderOption foreach { peer(_) ! TakeOverFromMe }
        setTimer(TakeOverRetryTimer, TakeOverRetry(count + 1), retryInterval, repeat = false)
        stay
      } else
        throw new ClusterSingletonManagerIsStuck(s"Expected hand-over to [${newLeaderOption}] never occured")

    case Event(HandOverToMe, WasLeaderData(singleton, singletonTerminated, handOverData, _)) ⇒
      gotoHandingOver(singleton, singletonTerminated, handOverData, Some(sender))

    case Event(MemberRemoved(m), WasLeaderData(singleton, singletonTerminated, handOverData, Some(newLeader))) if m.address == newLeader ⇒
      addRemoved(m.address)
      gotoHandingOver(singleton, singletonTerminated, handOverData, None)

    case Event(singletonHandOverMessage, d @ WasLeaderData(singleton, _, _, _)) if sender == singleton ⇒
      stay using d.copy(handOverData = Some(singletonHandOverMessage))

    case Event(Terminated(ref), d @ WasLeaderData(singleton, _, _, _)) if ref == singleton ⇒
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
    val newLeader = handOverTo.map(_.path.address)
    logInfo("Singleton terminated, hand-over done [{} -> {}]", cluster.selfAddress, newLeader)
    handOverTo foreach { _ ! HandOverDone(handOverData) }
    goto(NonLeader) using NonLeaderData(newLeader)
  }

  whenUnhandled {
    case Event(_: CurrentClusterState, _) ⇒ stay
    case Event(MemberRemoved(m), _) ⇒
      logInfo("Member removed [{}]", m.address)
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
    case _ -> BecomingLeader ⇒ setTimer(HandOverRetryTimer, HandOverRetry(1), retryInterval, repeat = false)
  }

  onTransition {
    case BecomingLeader -> _ ⇒ cancelTimer(HandOverRetryTimer)
    case WasLeader -> _      ⇒ cancelTimer(TakeOverRetryTimer)
  }

  onTransition {
    case _ -> (NonLeader | Leader) ⇒ getNextLeaderChanged()
  }

  onTransition {
    case _ -> NonLeader if removed.contains(cluster.selfAddress) ⇒
      logInfo("Self removed, stopping ClusterSingletonManager")
      stop()
  }

}