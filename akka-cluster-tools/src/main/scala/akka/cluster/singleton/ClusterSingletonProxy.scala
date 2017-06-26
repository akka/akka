/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.cluster.singleton

import akka.actor._
import akka.cluster.{ Cluster, Member, MemberStatus }
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
import akka.event.Logging
import akka.util.MessageBuffer

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
      singletonName = config.getString("singleton-name"),
      role = roleOption(config.getString("role")),
      singletonIdentificationInterval = config.getDuration("singleton-identification-interval", MILLISECONDS).millis,
      bufferSize = config.getInt("buffer-size"))

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
 * @param singletonName The actor name of the singleton actor that is started by the [[ClusterSingletonManager]].
 * @param role The role of the cluster nodes where the singleton can be deployed. If None, then any node will do.
 * @param singletonIdentificationInterval Interval at which the proxy will try to resolve the singleton instance.
 * @param bufferSize If the location of the singleton is unknown the proxy will buffer this number of messages
 *   and deliver them when the singleton is identified. When the buffer is full old messages will be dropped
 *   when new messages are sent viea the proxy. Use 0 to disable buffering, i.e. messages will be dropped
 *   immediately if the location of the singleton is unknown.
 */
final class ClusterSingletonProxySettings(
  val singletonName:                   String,
  val role:                            Option[String],
  val singletonIdentificationInterval: FiniteDuration,
  val bufferSize:                      Int) extends NoSerializationVerificationNeeded {

  require(bufferSize >= 0 && bufferSize <= 10000, "bufferSize must be >= 0 and <= 10000")

  def withSingletonName(name: String): ClusterSingletonProxySettings = copy(singletonName = name)

  def withRole(role: String): ClusterSingletonProxySettings = copy(role = ClusterSingletonProxySettings.roleOption(role))

  def withRole(role: Option[String]): ClusterSingletonProxySettings = copy(role = role)

  def withSingletonIdentificationInterval(singletonIdentificationInterval: FiniteDuration): ClusterSingletonProxySettings =
    copy(singletonIdentificationInterval = singletonIdentificationInterval)

  def withBufferSize(bufferSize: Int): ClusterSingletonProxySettings =
    copy(bufferSize = bufferSize)

  private def copy(
    singletonName:                   String         = singletonName,
    role:                            Option[String] = role,
    singletonIdentificationInterval: FiniteDuration = singletonIdentificationInterval,
    bufferSize:                      Int            = bufferSize): ClusterSingletonProxySettings =
    new ClusterSingletonProxySettings(singletonName, role, singletonIdentificationInterval, bufferSize)
}

object ClusterSingletonProxy {
  /**
   * Scala API: Factory method for `ClusterSingletonProxy` [[akka.actor.Props]].
   *
   * @param singletonManagerPath The logical path of the singleton manager, e.g. `/user/singletonManager`,
   *   which ends with the name you defined in `actorOf` when creating the [[ClusterSingletonManager]].
   * @param settings see [[ClusterSingletonProxySettings]]
   */
  def props(singletonManagerPath: String, settings: ClusterSingletonProxySettings): Props =
    Props(new ClusterSingletonProxy(singletonManagerPath, settings)).withDeploy(Deploy.local)

  private case object TryToIdentifySingleton

}

/**
 * The `ClusterSingletonProxy` works together with the [[akka.cluster.singleton.ClusterSingletonManager]] to provide a
 * distributed proxy to the singleton actor.
 *
 * The proxy can be started on every node where the singleton needs to be reached and used as if it were the singleton
 * itself. It will then act as a router to the currently running singleton instance. If the singleton is not currently
 * available, e.g., during hand off or startup, the proxy will buffer the messages sent to the singleton and then deliver
 * them when the singleton is finally available. The size of the buffer is configurable and it can be disabled by using
 * a buffer size of 0. When the buffer is full old messages will be dropped when new messages are sent via the proxy.
 *
 * The proxy works by keeping track of the oldest cluster member. When a new oldest member is identified, e.g. because
 * the older one left the cluster, or at startup, the proxy will try to identify the singleton on the oldest member by
 * periodically sending an [[akka.actor.Identify]] message until the singleton responds with its
 * [[akka.actor.ActorIdentity]].
 *
 * Note that this is a best effort implementation: messages can always be lost due to the distributed nature of the
 * actors involved.
 */
final class ClusterSingletonProxy(singletonManagerPath: String, settings: ClusterSingletonProxySettings) extends Actor with ActorLogging {
  import settings._
  val singletonPath = (singletonManagerPath + "/" + settings.singletonName).split("/")
  var identifyCounter = 0
  var identifyId = createIdentifyId(identifyCounter)
  def createIdentifyId(i: Int) = "identify-singleton-" + singletonPath.mkString("/") + i
  var identifyTimer: Option[Cancellable] = None

  val cluster = Cluster(context.system)
  var singleton: Option[ActorRef] = None
  // sort by age, oldest first
  val ageOrdering = Member.ageOrdering
  var membersByAge: immutable.SortedSet[Member] = immutable.SortedSet.empty(ageOrdering)

  var buffer: MessageBuffer = MessageBuffer.empty

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

  private val selfTeam = "team-" + cluster.settings.Team

  def matchingRole(member: Member): Boolean = member.hasRole(selfTeam) && (role match {
    case None    ⇒ true
    case Some(r) ⇒ member.hasRole(r)
  })

  def handleInitial(state: CurrentClusterState): Unit = {
    trackChange {
      () ⇒
        membersByAge = immutable.SortedSet.empty(ageOrdering) union state.members.collect {
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
      trackChange { () ⇒
        membersByAge -= m // replace
        membersByAge += m
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
    case MemberUp(m)                ⇒ add(m)
    case MemberExited(m)            ⇒ remove(m)
    case MemberRemoved(m, _) ⇒
      if (m.uniqueAddress == cluster.selfUniqueAddress)
        context.stop(self)
      else
        remove(m)
    case _: MemberEvent ⇒ // do nothing

    // singleton identification logic
    case ActorIdentity(identifyId, Some(s)) ⇒
      // if the new singleton is defined, deliver all buffered messages
      log.info("Singleton identified at [{}]", s.path)
      singleton = Some(s)
      context.watch(s)
      cancelTimer()
      sendBuffered()
    case _: ActorIdentity ⇒ // do nothing
    case ClusterSingletonProxy.TryToIdentifySingleton if identifyTimer.isDefined ⇒
      membersByAge.headOption.foreach {
        oldest ⇒
          val singletonAddress = RootActorPath(oldest.address) / singletonPath
          log.debug("Trying to identify singleton at [{}]", singletonAddress)
          context.actorSelection(singletonAddress) ! Identify(identifyId)
      }
    case Terminated(ref) ⇒
      if (singleton.exists(_ == ref)) {
        // buffering mode, identification of new will start when old node is removed
        singleton = None
      }

    // forwarding/stashing logic
    case msg: Any ⇒
      singleton match {
        case Some(s) ⇒
          if (log.isDebugEnabled)
            log.debug(
              "Forwarding message of type [{}] to current singleton instance at [{}]: {}",
              Logging.simpleName(msg.getClass), s.path)
          s forward msg
        case None ⇒
          buffer(msg)
      }
  }

  def buffer(msg: Any): Unit =
    if (settings.bufferSize == 0)
      log.debug("Singleton not available and buffering is disabled, dropping message [{}]", msg.getClass.getName)
    else if (buffer.size == settings.bufferSize) {
      val (m, _) = buffer.head()
      buffer.dropHead()
      log.debug("Singleton not available, buffer is full, dropping first message [{}]", m.getClass.getName)
      buffer.append(msg, sender())
    } else {
      log.debug("Singleton not available, buffering message type [{}]", msg.getClass.getName)
      buffer.append(msg, sender())
    }

  def sendBuffered(): Unit = {
    log.debug("Sending buffered messages to current singleton instance")
    val target = singleton.get
    buffer.foreach((msg, snd) ⇒ target.tell(msg, snd))
    buffer = MessageBuffer.empty
  }
}
