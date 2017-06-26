/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.sharding

import java.net.URLEncoder
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.Await
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Deploy
import akka.actor.ExtendedActorSystem
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider
import akka.actor.NoSerializationVerificationNeeded
import akka.actor.PoisonPill
import akka.actor.Props
import akka.cluster.Cluster
import akka.cluster.ddata.DistributedData
import akka.cluster.singleton.ClusterSingletonManager
import akka.pattern.BackoffSupervisor
import akka.util.ByteString
import akka.pattern.ask
import akka.dispatch.Dispatchers
import akka.cluster.ddata.ReplicatorSettings
import akka.cluster.ddata.Replicator
import scala.util.control.NonFatal
import akka.actor.Status
import akka.cluster.ClusterSettings

/**
 * This extension provides sharding functionality of actors in a cluster.
 * The typical use case is when you have many stateful actors that together consume
 * more resources (e.g. memory) than fit on one machine. You need to distribute them across
 * several nodes in the cluster and you want to be able to interact with them using their
 * logical identifier, but without having to care about their physical location in the cluster,
 * which might also change over time. It could for example be actors representing Aggregate Roots in
 * Domain-Driven Design terminology. Here we call these actors "entities". These actors
 * typically have persistent (durable) state, but this feature is not limited to
 * actors with persistent state.
 *
 * In this context sharding means that actors with an identifier, so called entities,
 * can be automatically distributed across multiple nodes in the cluster. Each entity
 * actor runs only at one place, and messages can be sent to the entity without requiring
 * the sender to know the location of the destination actor. This is achieved by sending
 * the messages via a [[ShardRegion]] actor provided by this extension, which knows how
 * to route the message with the entity id to the final destination.
 *
 * This extension is supposed to be used by first, typically at system startup on each node
 * in the cluster, registering the supported entity types with the [[ClusterSharding#start]]
 * method and then the `ShardRegion` actor for a named entity type can be retrieved with
 * [[ClusterSharding#shardRegion]]. Messages to the entities are always sent via the local
 * `ShardRegion`. Some settings can be configured as described in the `akka.cluster.sharding`
 * section of the `reference.conf`.
 *
 * The `ShardRegion` actor is started on each node in the cluster, or group of nodes
 * tagged with a specific role. The `ShardRegion` is created with two application specific
 * functions to extract the entity identifier and the shard identifier from incoming messages.
 * A shard is a group of entities that will be managed together. For the first message in a
 * specific shard the `ShardRegion` request the location of the shard from a central coordinator,
 * the [[ShardCoordinator]]. The `ShardCoordinator` decides which `ShardRegion` that
 * owns the shard. The `ShardRegion` receives the decided home of the shard
 * and if that is the `ShardRegion` instance itself it will create a local child
 * actor representing the entity and direct all messages for that entity to it.
 * If the shard home is another `ShardRegion` instance messages will be forwarded
 * to that `ShardRegion` instance instead. While resolving the location of a
 * shard incoming messages for that shard are buffered and later delivered when the
 * shard home is known. Subsequent messages to the resolved shard can be delivered
 * to the target destination immediately without involving the `ShardCoordinator`.
 *
 * To make sure that at most one instance of a specific entity actor is running somewhere
 * in the cluster it is important that all nodes have the same view of where the shards
 * are located. Therefore the shard allocation decisions are taken by the central
 * `ShardCoordinator`, which is running as a cluster singleton, i.e. one instance on
 * the oldest member among all cluster nodes or a group of nodes tagged with a specific
 * role. The oldest member can be determined by [[akka.cluster.Member#isOlderThan]].
 *
 * The logic that decides where a shard is to be located is defined in a pluggable shard
 * allocation strategy. The default implementation [[ShardCoordinator.LeastShardAllocationStrategy]]
 * allocates new shards to the `ShardRegion` with least number of previously allocated shards.
 * This strategy can be replaced by an application specific implementation.
 *
 * To be able to use newly added members in the cluster the coordinator facilitates rebalancing
 * of shards, i.e. migrate entities from one node to another. In the rebalance process the
 * coordinator first notifies all `ShardRegion` actors that a handoff for a shard has started.
 * That means they will start buffering incoming messages for that shard, in the same way as if the
 * shard location is unknown. During the rebalance process the coordinator will not answer any
 * requests for the location of shards that are being rebalanced, i.e. local buffering will
 * continue until the handoff is completed. The `ShardRegion` responsible for the rebalanced shard
 * will stop all entities in that shard by sending `PoisonPill` to them. When all entities have
 * been terminated the `ShardRegion` owning the entities will acknowledge the handoff as completed
 * to the coordinator. Thereafter the coordinator will reply to requests for the location of
 * the shard and thereby allocate a new home for the shard and then buffered messages in the
 * `ShardRegion` actors are delivered to the new location. This means that the state of the entities
 * are not transferred or migrated. If the state of the entities are of importance it should be
 * persistent (durable), e.g. with `akka-persistence`, so that it can be recovered at the new
 * location.
 *
 * The logic that decides which shards to rebalance is defined in a pluggable shard
 * allocation strategy. The default implementation [[ShardCoordinator.LeastShardAllocationStrategy]]
 * picks shards for handoff from the `ShardRegion` with most number of previously allocated shards.
 * They will then be allocated to the `ShardRegion` with least number of previously allocated shards,
 * i.e. new members in the cluster. There is a configurable threshold of how large the difference
 * must be to begin the rebalancing. This strategy can be replaced by an application specific
 * implementation.
 *
 * The state of shard locations in the `ShardCoordinator` is persistent (durable) with
 * `akka-persistence` to survive failures. Since it is running in a cluster `akka-persistence`
 * must be configured with a distributed journal. When a crashed or unreachable coordinator
 * node has been removed (via down) from the cluster a new `ShardCoordinator` singleton
 * actor will take over and the state is recovered. During such a failure period shards
 * with known location are still available, while messages for new (unknown) shards
 * are buffered until the new `ShardCoordinator` becomes available.
 *
 * As long as a sender uses the same `ShardRegion` actor to deliver messages to an entity
 * actor the order of the messages is preserved. As long as the buffer limit is not reached
 * messages are delivered on a best effort basis, with at-most once delivery semantics,
 * in the same way as ordinary message sending. Reliable end-to-end messaging, with
 * at-least-once semantics can be added by using `AtLeastOnceDelivery` in `akka-persistence`.
 *
 * Some additional latency is introduced for messages targeted to new or previously
 * unused shards due to the round-trip to the coordinator. Rebalancing of shards may
 * also add latency. This should be considered when designing the application specific
 * shard resolution, e.g. to avoid too fine grained shards.
 *
 * The `ShardRegion` actor can also be started in proxy only mode, i.e. it will not
 * host any entities itself, but knows how to delegate messages to the right location.
 *
 * If the state of the entities are persistent you may stop entities that are not used to
 * reduce memory consumption. This is done by the application specific implementation of
 * the entity actors for example by defining receive timeout (`context.setReceiveTimeout`).
 * If a message is already enqueued to the entity when it stops itself the enqueued message
 * in the mailbox will be dropped. To support graceful passivation without losing such
 * messages the entity actor can send [[ShardRegion.Passivate]] to its parent `ShardRegion`.
 * The specified wrapped message in `Passivate` will be sent back to the entity, which is
 * then supposed to stop itself. Incoming messages will be buffered by the `ShardRegion`
 * between reception of `Passivate` and termination of the entity. Such buffered messages
 * are thereafter delivered to a new incarnation of the entity.
 *
 */
object ClusterSharding extends ExtensionId[ClusterSharding] with ExtensionIdProvider {
  override def get(system: ActorSystem): ClusterSharding = super.get(system)

  override def lookup = ClusterSharding

  override def createExtension(system: ExtendedActorSystem): ClusterSharding =
    new ClusterSharding(system)

}

/**
 * @see [[ClusterSharding$ ClusterSharding companion object]]
 */
class ClusterSharding(system: ExtendedActorSystem) extends Extension {
  import ClusterShardingGuardian._
  import ShardCoordinator.ShardAllocationStrategy
  import ShardCoordinator.LeastShardAllocationStrategy

  private val cluster = Cluster(system)

  private val regions: ConcurrentHashMap[String, ActorRef] = new ConcurrentHashMap
  private lazy val guardian = {
    val guardianName: String = system.settings.config.getString("akka.cluster.sharding.guardian-name")
    val dispatcher = system.settings.config.getString("akka.cluster.sharding.use-dispatcher") match {
      case "" ⇒ Dispatchers.DefaultDispatcherId
      case id ⇒ id
    }
    system.systemActorOf(Props[ClusterShardingGuardian].withDispatcher(dispatcher), guardianName)
  }

  private[akka] def requireClusterRole(role: Option[String]): Unit =
    require(
      role.forall(cluster.selfRoles.contains),
      s"This cluster member [${cluster.selfAddress}] doesn't have the role [$role]")

  /**
   * Scala API: Register a named entity type by defining the [[akka.actor.Props]] of the entity actor
   * and functions to extract entity and shard identifier from messages. The [[ShardRegion]] actor
   * for this type can later be retrieved with the [[#shardRegion]] method.
   *
   * Some settings can be configured as described in the `akka.cluster.sharding` section
   * of the `reference.conf`.
   *
   * @param typeName the name of the entity type
   * @param entityProps the `Props` of the entity actors that will be created by the `ShardRegion`
   * @param settings configuration settings, see [[ClusterShardingSettings]]
   * @param extractEntityId partial function to extract the entity id and the message to send to the
   *   entity from the incoming message, if the partial function does not match the message will
   *   be `unhandled`, i.e. posted as `Unhandled` messages on the event stream
   * @param extractShardId function to determine the shard id for an incoming message, only messages
   *   that passed the `extractEntityId` will be used
   * @param allocationStrategy possibility to use a custom shard allocation and
   *   rebalancing logic
   * @param handOffStopMessage the message that will be sent to entities when they are to be stopped
   *   for a rebalance or graceful shutdown of a `ShardRegion`, e.g. `PoisonPill`.
   * @return the actor ref of the [[ShardRegion]] that is to be responsible for the shard
   */
  def start(
    typeName:           String,
    entityProps:        Props,
    settings:           ClusterShardingSettings,
    extractEntityId:    ShardRegion.ExtractEntityId,
    extractShardId:     ShardRegion.ExtractShardId,
    allocationStrategy: ShardAllocationStrategy,
    handOffStopMessage: Any): ActorRef = {

    requireClusterRole(settings.role)
    implicit val timeout = system.settings.CreationTimeout
    val startMsg = Start(typeName, entityProps, settings,
      extractEntityId, extractShardId, allocationStrategy, handOffStopMessage)
    val Started(shardRegion) = Await.result(guardian ? startMsg, timeout.duration)
    regions.put(typeName, shardRegion)
    shardRegion
  }

  /**
   * Register a named entity type by defining the [[akka.actor.Props]] of the entity actor and
   * functions to extract entity and shard identifier from messages. The [[ShardRegion]] actor
   * for this type can later be retrieved with the [[#shardRegion]] method.
   *
   * The default shard allocation strategy [[ShardCoordinator.LeastShardAllocationStrategy]]
   * is used. [[akka.actor.PoisonPill]] is used as `handOffStopMessage`.
   *
   * Some settings can be configured as described in the `akka.cluster.sharding` section
   * of the `reference.conf`.
   *
   * @param typeName the name of the entity type
   * @param entityProps the `Props` of the entity actors that will be created by the `ShardRegion`
   * @param settings configuration settings, see [[ClusterShardingSettings]]
   * @param extractEntityId partial function to extract the entity id and the message to send to the
   *   entity from the incoming message, if the partial function does not match the message will
   *   be `unhandled`, i.e. posted as `Unhandled` messages on the event stream
   * @param extractShardId function to determine the shard id for an incoming message, only messages
   *   that passed the `extractEntityId` will be used
   * @return the actor ref of the [[ShardRegion]] that is to be responsible for the shard
   */
  def start(
    typeName:        String,
    entityProps:     Props,
    settings:        ClusterShardingSettings,
    extractEntityId: ShardRegion.ExtractEntityId,
    extractShardId:  ShardRegion.ExtractShardId): ActorRef = {

    val allocationStrategy = new LeastShardAllocationStrategy(
      settings.tuningParameters.leastShardAllocationRebalanceThreshold,
      settings.tuningParameters.leastShardAllocationMaxSimultaneousRebalance)

    start(typeName, entityProps, settings, extractEntityId, extractShardId, allocationStrategy, PoisonPill)
  }

  /**
   * Java/Scala API: Register a named entity type by defining the [[akka.actor.Props]] of the entity actor
   * and functions to extract entity and shard identifier from messages. The [[ShardRegion]] actor
   * for this type can later be retrieved with the [[#shardRegion]] method.
   *
   * Some settings can be configured as described in the `akka.cluster.sharding` section
   * of the `reference.conf`.
   *
   * @param typeName the name of the entity type
   * @param entityProps the `Props` of the entity actors that will be created by the `ShardRegion`
   * @param settings configuration settings, see [[ClusterShardingSettings]]
   * @param messageExtractor functions to extract the entity id, shard id, and the message to send to the
   *   entity from the incoming message, see [[ShardRegion.MessageExtractor]]
   * @param allocationStrategy possibility to use a custom shard allocation and
   *   rebalancing logic
   * @param handOffStopMessage the message that will be sent to entities when they are to be stopped
   *   for a rebalance or graceful shutdown of a `ShardRegion`, e.g. `PoisonPill`.
   * @return the actor ref of the [[ShardRegion]] that is to be responsible for the shard
   */
  def start(
    typeName:           String,
    entityProps:        Props,
    settings:           ClusterShardingSettings,
    messageExtractor:   ShardRegion.MessageExtractor,
    allocationStrategy: ShardAllocationStrategy,
    handOffStopMessage: Any): ActorRef = {

    start(typeName, entityProps, settings,
      extractEntityId = {
      case msg if messageExtractor.entityId(msg) ne null ⇒
        (messageExtractor.entityId(msg), messageExtractor.entityMessage(msg))
    },
      extractShardId = msg ⇒ messageExtractor.shardId(msg),
      allocationStrategy = allocationStrategy,
      handOffStopMessage = handOffStopMessage)
  }

  /**
   * Java/Scala API: Register a named entity type by defining the [[akka.actor.Props]] of the entity actor
   * and functions to extract entity and shard identifier from messages. The [[ShardRegion]] actor
   * for this type can later be retrieved with the [[#shardRegion]] method.
   *
   * The default shard allocation strategy [[ShardCoordinator.LeastShardAllocationStrategy]]
   * is used. [[akka.actor.PoisonPill]] is used as `handOffStopMessage`.
   *
   * Some settings can be configured as described in the `akka.cluster.sharding` section
   * of the `reference.conf`.
   *
   * @param typeName the name of the entity type
   * @param entityProps the `Props` of the entity actors that will be created by the `ShardRegion`
   * @param settings configuration settings, see [[ClusterShardingSettings]]
   * @param messageExtractor functions to extract the entity id, shard id, and the message to send to the
   *   entity from the incoming message
   * @return the actor ref of the [[ShardRegion]] that is to be responsible for the shard
   */
  def start(
    typeName:         String,
    entityProps:      Props,
    settings:         ClusterShardingSettings,
    messageExtractor: ShardRegion.MessageExtractor): ActorRef = {

    val allocationStrategy = new LeastShardAllocationStrategy(
      settings.tuningParameters.leastShardAllocationRebalanceThreshold,
      settings.tuningParameters.leastShardAllocationMaxSimultaneousRebalance)

    start(typeName, entityProps, settings, messageExtractor, allocationStrategy, PoisonPill)
  }

  /**
   * Scala API: Register a named entity type `ShardRegion` on this node that will run in proxy only mode,
   * i.e. it will delegate messages to other `ShardRegion` actors on other nodes, but not host any
   * entity actors itself. The [[ShardRegion]] actor for this type can later be retrieved with the
   * [[#shardRegion]] method.
   *
   * Some settings can be configured as described in the `akka.cluster.sharding` section
   * of the `reference.conf`.
   *
   * @param typeName the name of the entity type
   * @param role specifies that this entity type is located on cluster nodes with a specific role.
   *   If the role is not specified all nodes in the cluster are used.
   * @param extractEntityId partial function to extract the entity id and the message to send to the
   *   entity from the incoming message, if the partial function does not match the message will
   *   be `unhandled`, i.e. posted as `Unhandled` messages on the event stream
   * @param extractShardId function to determine the shard id for an incoming message, only messages
   *   that passed the `extractEntityId` will be used
   * @return the actor ref of the [[ShardRegion]] that is to be responsible for the shard
   */
  def startProxy(
    typeName:        String,
    role:            Option[String],
    extractEntityId: ShardRegion.ExtractEntityId,
    extractShardId:  ShardRegion.ExtractShardId): ActorRef =
    startProxy(typeName, role, team = None, extractEntityId, extractShardId)

  /**
   * Scala API: Register a named entity type `ShardRegion` on this node that will run in proxy only mode,
   * i.e. it will delegate messages to other `ShardRegion` actors on other nodes, but not host any
   * entity actors itself. The [[ShardRegion]] actor for this type can later be retrieved with the
   * [[#shardRegion]] method.
   *
   * Some settings can be configured as described in the `akka.cluster.sharding` section
   * of the `reference.conf`.
   *
   * @param typeName the name of the entity type
   * @param role specifies that this entity type is located on cluster nodes with a specific role.
   *   If the role is not specified all nodes in the cluster are used.
   * @param team The team of the cluster nodes where the cluster sharding is running.
   *   If None then the same team as current node.
   * @param extractEntityId partial function to extract the entity id and the message to send to the
   *   entity from the incoming message, if the partial function does not match the message will
   *   be `unhandled`, i.e. posted as `Unhandled` messages on the event stream
   * @param extractShardId function to determine the shard id for an incoming message, only messages
   *   that passed the `extractEntityId` will be used
   * @return the actor ref of the [[ShardRegion]] that is to be responsible for the shard
   */
  def startProxy(
    typeName:        String,
    role:            Option[String],
    team:            Option[String],
    extractEntityId: ShardRegion.ExtractEntityId,
    extractShardId:  ShardRegion.ExtractShardId): ActorRef = {

    implicit val timeout = system.settings.CreationTimeout
    val settings = ClusterShardingSettings(system).withRole(role)
    val startMsg = StartProxy(typeName, team, settings, extractEntityId, extractShardId)
    val Started(shardRegion) = Await.result(guardian ? startMsg, timeout.duration)
    // it must be possible to start several proxies, one per team
    regions.put(proxyName(typeName, team), shardRegion)
    shardRegion
  }

  private def proxyName(typeName: String, team: Option[String]): String = {
    team match {
      case None    ⇒ typeName
      case Some(t) ⇒ typeName + "-" + t
    }
  }

  /**
   * Java/Scala API: Register a named entity type `ShardRegion` on this node that will run in proxy only mode,
   * i.e. it will delegate messages to other `ShardRegion` actors on other nodes, but not host any
   * entity actors itself. The [[ShardRegion]] actor for this type can later be retrieved with the
   * [[#shardRegion]] method.
   *
   * Some settings can be configured as described in the `akka.cluster.sharding` section
   * of the `reference.conf`.
   *
   * @param typeName the name of the entity type
   * @param role specifies that this entity type is located on cluster nodes with a specific role.
   *   If the role is not specified all nodes in the cluster are used.
   * @param messageExtractor functions to extract the entity id, shard id, and the message to send to the
   *   entity from the incoming message
   * @return the actor ref of the [[ShardRegion]] that is to be responsible for the shard
   */
  def startProxy(
    typeName:         String,
    role:             Optional[String],
    messageExtractor: ShardRegion.MessageExtractor): ActorRef =
    startProxy(typeName, role, team = Optional.empty(), messageExtractor)

  /**
   * Java/Scala API: Register a named entity type `ShardRegion` on this node that will run in proxy only mode,
   * i.e. it will delegate messages to other `ShardRegion` actors on other nodes, but not host any
   * entity actors itself. The [[ShardRegion]] actor for this type can later be retrieved with the
   * [[#shardRegion]] method.
   *
   * Some settings can be configured as described in the `akka.cluster.sharding` section
   * of the `reference.conf`.
   *
   * @param typeName the name of the entity type
   * @param role specifies that this entity type is located on cluster nodes with a specific role.
   *   If the role is not specified all nodes in the cluster are used.
   * @param team The team of the cluster nodes where the cluster sharding is running.
   *   If None then the same team as current node.
   * @param messageExtractor functions to extract the entity id, shard id, and the message to send to the
   *   entity from the incoming message
   * @return the actor ref of the [[ShardRegion]] that is to be responsible for the shard
   */
  def startProxy(
    typeName:         String,
    role:             Optional[String],
    team:             Optional[String],
    messageExtractor: ShardRegion.MessageExtractor): ActorRef = {

    startProxy(typeName, Option(role.orElse(null)), Option(team.orElse(null)),
      extractEntityId = {
      case msg if messageExtractor.entityId(msg) ne null ⇒
        (messageExtractor.entityId(msg), messageExtractor.entityMessage(msg))
    },
      extractShardId = msg ⇒ messageExtractor.shardId(msg))

  }

  /**
   * Retrieve the actor reference of the [[ShardRegion]] actor responsible for the named entity type.
   * The entity type must be registered with the [[#start]] or [[#startProxy]] method before it
   * can be used here. Messages to the entity is always sent via the `ShardRegion`.
   */
  def shardRegion(typeName: String): ActorRef = regions.get(typeName) match {
    case null ⇒ throw new IllegalArgumentException(s"Shard type [$typeName] must be started first")
    case ref  ⇒ ref
  }

  /**
   * Retrieve the actor reference of the [[ShardRegion]] actor that will act as a proxy to the
   * named entity type running in another team. A proxy within the same team can be accessed
   * with [[#shardRegion]] instead of this method. The entity type must be registered with the
   * [[#startProxy]] method before it can be used here. Messages to the entity is always sent
   * via the `ShardRegion`.
   */
  def shardRegionProxy(typeName: String, team: String): ActorRef = {
    regions.get(proxyName(typeName, Some(team))) match {
      case null ⇒ throw new IllegalArgumentException(s"Shard type [$typeName] must be started first")
      case ref  ⇒ ref
    }
  }

}

/**
 * INTERNAL API.
 */
private[akka] object ClusterShardingGuardian {
  import ShardCoordinator.ShardAllocationStrategy
  final case class Start(typeName: String, entityProps: Props, settings: ClusterShardingSettings,
                         extractEntityId: ShardRegion.ExtractEntityId, extractShardId: ShardRegion.ExtractShardId,
                         allocationStrategy: ShardAllocationStrategy, handOffStopMessage: Any)
    extends NoSerializationVerificationNeeded
  final case class StartProxy(typeName: String, team: Option[String], settings: ClusterShardingSettings,
                              extractEntityId: ShardRegion.ExtractEntityId, extractShardId: ShardRegion.ExtractShardId)
    extends NoSerializationVerificationNeeded
  final case class Started(shardRegion: ActorRef) extends NoSerializationVerificationNeeded
}

/**
 * INTERNAL API. [[ShardRegion]] and [[ShardCoordinator]] actors are created as children
 * of this actor.
 */
private[akka] class ClusterShardingGuardian extends Actor {
  import ClusterShardingGuardian._

  val cluster = Cluster(context.system)
  val sharding = ClusterSharding(context.system)

  val majorityMinCap = context.system.settings.config.getInt(
    "akka.cluster.sharding.distributed-data.majority-min-cap")
  private lazy val replicatorSettings =
    ReplicatorSettings(context.system.settings.config.getConfig(
      "akka.cluster.sharding.distributed-data"))
  private var replicatorByRole = Map.empty[Option[String], ActorRef]

  private def coordinatorSingletonManagerName(encName: String): String =
    encName + "Coordinator"

  private def coordinatorPath(encName: String): String =
    (self.path / coordinatorSingletonManagerName(encName) / "singleton" / "coordinator").toStringWithoutAddress

  private def replicator(settings: ClusterShardingSettings): ActorRef = {
    if (settings.stateStoreMode == ClusterShardingSettings.StateStoreModeDData) {
      // one Replicator per role
      replicatorByRole.get(settings.role) match {
        case Some(ref) ⇒ ref
        case None ⇒
          val name = settings.role match {
            case Some(r) ⇒ URLEncoder.encode(r, ByteString.UTF_8) + "Replicator"
            case None    ⇒ "replicator"
          }
          // Use members within the team and with the given role (if any)
          val replicatorRoles = Set(ClusterSettings.TeamRolePrefix + cluster.settings.Team) ++ settings.role
          val ref = context.actorOf(Replicator.props(replicatorSettings.withRoles(replicatorRoles)), name)
          replicatorByRole = replicatorByRole.updated(settings.role, ref)
          ref
      }
    } else
      context.system.deadLetters
  }

  def receive = {
    case Start(typeName, entityProps, settings, extractEntityId, extractShardId, allocationStrategy, handOffStopMessage) ⇒
      try {
        import settings.role
        import settings.tuningParameters.coordinatorFailureBackoff

        val rep = replicator(settings)
        val encName = URLEncoder.encode(typeName, ByteString.UTF_8)
        val cName = coordinatorSingletonManagerName(encName)
        val cPath = coordinatorPath(encName)
        val shardRegion = context.child(encName).getOrElse {
          if (context.child(cName).isEmpty) {
            val coordinatorProps =
              if (settings.stateStoreMode == ClusterShardingSettings.StateStoreModePersistence)
                ShardCoordinator.props(typeName, settings, allocationStrategy)
              else {
                ShardCoordinator.props(typeName, settings, allocationStrategy, rep, majorityMinCap)
              }
            val singletonProps = BackoffSupervisor.props(
              childProps = coordinatorProps,
              childName = "coordinator",
              minBackoff = coordinatorFailureBackoff,
              maxBackoff = coordinatorFailureBackoff * 5,
              randomFactor = 0.2).withDeploy(Deploy.local)
            val singletonSettings = settings.coordinatorSingletonSettings
              .withSingletonName("singleton").withRole(role)
            context.actorOf(
              ClusterSingletonManager.props(
                singletonProps,
                terminationMessage = PoisonPill,
                singletonSettings).withDispatcher(context.props.dispatcher),
              name = cName)
          }

          context.actorOf(
            ShardRegion.props(
              typeName = typeName,
              entityProps = entityProps,
              settings = settings,
              coordinatorPath = cPath,
              extractEntityId = extractEntityId,
              extractShardId = extractShardId,
              handOffStopMessage = handOffStopMessage,
              replicator = rep,
              majorityMinCap).withDispatcher(context.props.dispatcher),
            name = encName)
        }
        sender() ! Started(shardRegion)
      } catch {
        case NonFatal(e) ⇒
          // don't restart
          // could be invalid ReplicatorSettings, or InvalidActorNameException
          // if it has already been started
          sender() ! Status.Failure(e)
      }

    case StartProxy(typeName, team, settings, extractEntityId, extractShardId) ⇒
      try {

        val encName = URLEncoder.encode(typeName, ByteString.UTF_8)
        val cName = coordinatorSingletonManagerName(encName)
        val cPath = coordinatorPath(encName)
        // it must be possible to start several proxies, one per team
        val actorName = team match {
          case None    ⇒ encName
          case Some(t) ⇒ URLEncoder.encode(typeName + "-" + t, ByteString.UTF_8)
        }
        val shardRegion = context.child(actorName).getOrElse {
          context.actorOf(
            ShardRegion.proxyProps(
              typeName = typeName,
              team = team,
              settings = settings,
              coordinatorPath = cPath,
              extractEntityId = extractEntityId,
              extractShardId = extractShardId,
              replicator = context.system.deadLetters,
              majorityMinCap).withDispatcher(context.props.dispatcher),
            name = actorName)
        }
        sender() ! Started(shardRegion)
      } catch {
        case NonFatal(e) ⇒
          // don't restart
          // could be InvalidActorNameException if it has already been started
          sender() ! Status.Failure(e)
      }

  }

}

