/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster.singleton

import akka.actor._
import akka.cluster.{ MemberStatus, Cluster, Member }
import scala.collection.immutable
import akka.cluster.ClusterEvent._
import akka.cluster.ClusterEvent.MemberRemoved
import akka.cluster.ClusterEvent.MemberUp
import akka.actor.RootActorPath
import akka.cluster.ClusterEvent.CurrentClusterState
import akka.cluster.ClusterEvent.MemberExited
import scala.concurrent.duration._
import scala.language.postfixOps
import com.typesafe.config.Config
import akka.actor.NoSerializationVerificationNeeded

object ClusterSingletonProxySettings {

  /**
   * Create settings from the default configuration
   * `akka.cluster.singleton-proxy`.
   */
  def apply(system: ActorSystem): ClusterSingletonProxySettings =
    apply(system.settings.config.getConfig("akka.cluster.singleton-proxy"))

  /**
   * Create settings from a configuration with the same layout as
   * the default configuration `akka.cluster.singleton-proxy`.
   */
  def apply(config: Config): ClusterSingletonProxySettings =
    new ClusterSingletonProxySettings(
      role = roleOption(config.getString("role")),
      singletonIdentificationInterval = config.getDuration("singleton-identification-interval", MILLISECONDS).millis)

  /**
   * Java API: Create settings from the default configuration
   * `akka.cluster.singleton-proxy`.
   */
  def create(system: ActorSystem): ClusterSingletonProxySettings = apply(system)

  /**
   * Java API: Create settings from a configuration with the same layout as
   * the default configuration `akka.cluster.singleton-proxy`.
   */
  def create(config: Config): ClusterSingletonProxySettings = apply(config)

  /**
   * INTERNAL API
   */
  private[akka] def roleOption(role: String): Option[String] =
    if (role == "") None else Option(role)

}

/**
 * @param role The role of the cluster nodes where the singleton can be deployed. If None, then any node will do.
 * @param singletonIdentificationInterval Interval at which the proxy will try to resolve the singleton instance.
 */
final class ClusterSingletonProxySettings(
  val role: Option[String],
  val singletonIdentificationInterval: FiniteDuration) extends NoSerializationVerificationNeeded {

  def withRole(role: String): ClusterSingletonProxySettings = copy(role = ClusterSingletonProxySettings.roleOption(role))

  def withRole(role: Option[String]): ClusterSingletonProxySettings = copy(role = role)

  def withSingletonIdentificationInterval(singletonIdentificationInterval: FiniteDuration): ClusterSingletonProxySettings =
    copy(singletonIdentificationInterval = singletonIdentificationInterval)

  private def copy(role: Option[String] = role,
                   singletonIdentificationInterval: FiniteDuration = singletonIdentificationInterval): ClusterSingletonProxySettings =
    new ClusterSingletonProxySettings(role, singletonIdentificationInterval)
}

object ClusterSingletonProxy {
  /**
   * Scala API: Factory method for `ClusterSingletonProxy` [[akka.actor.Props]].
   *
   * @param singletonPath The logical path of the singleton, i.e., /user/singletonManager/singleton.
   * @param settings see [[ClusterSingletonProxySettings]]
   */
  def props(singletonPath: String, settings: ClusterSingletonProxySettings): Props =
    Props(new ClusterSingletonProxy(singletonPath, settings)).withDeploy(Deploy.local)

  private case object TryToIdentifySingleton

}

/**
 * The `ClusterSingletonProxy` works together with the [[akka.cluster.singleton.ClusterSingletonManager]] to provide a
 * distributed proxy to the singleton actor.
 *
 * The proxy can be started on every node where the singleton needs to be reached and used as if it were the singleton
 * itself. It will then act as a router to the currently running singleton instance. If the singleton is not currently
 * available, e.g., during hand off or startup, the proxy will stash the messages sent to the singleton and then unstash
 * them when the singleton is finally available. The proxy mixes in the [[akka.actor.Stash]] trait, so it can be
 * configured accordingly.
 *
 * The proxy works by keeping track of the oldest cluster member. When a new oldest member is identified, e.g., because
 * the older one left the cluster, or at startup, the proxy will try to identify the singleton on the oldest member by
 * periodically sending an [[akka.actor.Identify]] message until the singleton responds with its
 * [[akka.actor.ActorIdentity]].
 *
 * Note that this is a best effort implementation: messages can always be lost due to the distributed nature of the
 * actors involved.
 */
class ClusterSingletonProxy(singletonPathString: String, settings: ClusterSingletonProxySettings) extends Actor with Stash with ActorLogging {
  import settings._
  val singletonPath = singletonPathString.split("/")
  var identifyCounter = 0
  var identifyId = createIdentifyId(identifyCounter)
  def createIdentifyId(i: Int) = "identify-singleton-" + singletonPath mkString "/" + i
  var identifyTimer: Option[Cancellable] = None

  val cluster = Cluster(context.system)
  var singleton: Option[ActorRef] = None
  // sort by age, oldest first
  val ageOrdering = Ordering.fromLessThan[Member] {
    (a, b) ⇒ a.isOlderThan(b)
  }
  var membersByAge: immutable.SortedSet[Member] = immutable.SortedSet.empty(ageOrdering)

  // subscribe to MemberEvent, re-subscribe when restart
  override def preStart(): Unit = {
    cancelTimer()
    cluster.subscribe(self, classOf[MemberEvent])
  }

  override def postStop(): Unit = {
    cancelTimer()
    cluster.unsubscribe(self)
  }

  def cancelTimer() = {
    identifyTimer.foreach(_.cancel())
    identifyTimer = None
  }

  def matchingRole(member: Member): Boolean = role match {
    case None    ⇒ true
    case Some(r) ⇒ member.hasRole(r)
  }

  def handleInitial(state: CurrentClusterState): Unit = {
    trackChange {
      () ⇒
        membersByAge = immutable.SortedSet.empty(ageOrdering) ++ state.members.collect {
          case m if m.status == MemberStatus.Up && matchingRole(m) ⇒ m
        }
    }
  }

  /**
   * Discard old singleton ActorRef and send a periodic message to self to identify the singleton.
   */
  def identifySingleton() {
    import context.dispatcher
    log.debug("Creating singleton identification timer...")
    identifyCounter += 1
    identifyId = createIdentifyId(identifyCounter)
    singleton = None
    cancelTimer()
    identifyTimer = Some(context.system.scheduler.schedule(0 milliseconds, singletonIdentificationInterval, self, ClusterSingletonProxy.TryToIdentifySingleton))
  }

  def trackChange(block: () ⇒ Unit): Unit = {
    val before = membersByAge.headOption
    block()
    val after = membersByAge.headOption
    // if the head has changed, I need to find the new singleton
    if (before != after) identifySingleton()
  }

  /**
   * Adds new member if it has the right role.
   * @param m New cluster member.
   */
  def add(m: Member): Unit = {
    if (matchingRole(m))
      trackChange {
        () ⇒ membersByAge += m
      }
  }

  /**
   * Removes a member.
   * @param m Cluster member to remove.
   */
  def remove(m: Member): Unit = {
    if (matchingRole(m))
      trackChange {
        () ⇒ membersByAge -= m
      }
  }

  def receive = {
    // cluster logic
    case state: CurrentClusterState ⇒ handleInitial(state)
    case MemberUp(m) ⇒ add(m)
    case mEvent: MemberEvent if mEvent.isInstanceOf[MemberExited] || mEvent.isInstanceOf[MemberRemoved] ⇒ remove(mEvent.member)
    case _: MemberEvent ⇒ // do nothing

    // singleton identification logic
    case ActorIdentity(identifyId, Some(s)) ⇒
      // if the new singleton is defined, unstash all messages
      log.info("Singleton identified: {}", s.path)
      singleton = Some(s)
      cancelTimer()
      unstashAll()
    case _: ActorIdentity ⇒ // do nothing
    case ClusterSingletonProxy.TryToIdentifySingleton if identifyTimer.isDefined ⇒
      membersByAge.headOption.foreach {
        oldest ⇒
          val singletonAddress = RootActorPath(oldest.address) / singletonPath
          log.debug("Trying to identify singleton at {}", singletonAddress)
          context.actorSelection(singletonAddress) ! Identify(identifyId)
      }

    // forwarding/stashing logic
    case msg: Any ⇒
      singleton match {
        case Some(s) ⇒
          log.debug("Forwarding message to current singleton instance {}", msg)
          s forward msg
        case None ⇒
          log.debug("No singleton available, stashing message {}", msg)
          stash()
      }
  }
}
