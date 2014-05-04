/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.datareplication

import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.forkjoin.ThreadLocalRandom
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSelection
import akka.actor.Props
import akka.actor.ReceiveTimeout
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.cluster.Member
import akka.cluster.MemberStatus
import scala.util.Try
import scala.util.Success
import scala.util.Failure
import java.net.URLEncoder
import scala.util.control.NoStackTrace
import akka.util.ByteString
import akka.serialization.SerializationExtension
import java.security.MessageDigest
import akka.cluster.ClusterEvent.InitialStateAsEvents
import akka.actor.Address
import akka.cluster.UniqueAddress
import akka.actor.Terminated

/**
 * A replicated in-memory data store supporting low latency and high availability
 * requirements.
 *
 * The [[Replicator]] actor takes care of direct replication and gossip based
 * dissemination of Conflict Free Replicated Data Types (CRDT) to replicas in the
 * the cluster.
 * The data types must be convergent CRDTs and implement [[ReplicatedData]], i.e.
 * they provide a monotonic merge function and the state changes always converge.
 *
 * You can use your own custom [[ReplicatedData]] types, and several types are provided
 * by this package, such as:
 *
 * <ul>
 * <li>Counters: [[GCounter]], [[PNCounter]]</li>
 * <li>Registers: [[LWWRegister]], [[Flag]]</li>
 * <li>Sets: [[GSet]], [[TwoPhaseSet]], [[ORSet]]</li>
 * <li>Maps: [[ORMap]], [[LWWMap]], [[PNCounterMap]]</li>
 * </ul>
 *
 * For good introduction to the CRDT subject watch the
 * <a href="http://vimeo.com/43903960">Eventually Consistent Data Structures</a>
 * talk by Sean Cribbs and and the
 * <a href="http://research.microsoft.com/apps/video/dl.aspx?id=153540">talk by Mark Shapiro</a>
 * and the excellent paper <a href="http://hal.upmc.fr/docs/00/55/55/88/PDF/techreport.pdf">
 * A comprehensive study of Convergent and Commutative Replicated Data Types</a>
 * by Mark Shapiro et. al.
 *
 * The `Replicator` actor is started on each node in the cluster, or group of
 * nodes tagged with a specific role. It communicates with other `Replicator` instances
 * with the same path (without address) that are running on other nodes . For convenience it
 * can be used with the [[DataReplication]] extension.
 *
 * A modified [[ReplicatedData]] instance is replicated by sending it
 * in a [[Replicator.Update]] message to the the local `Replicator`.
 * You supply a consistency level which has the following meaning:
 * <ul>
 * <li>`WriteOne` the value will immediately only be written to the local replica,
 *     and later disseminated with gossip</li>
 * <li>`WriteTwo` the value will immediately be written to at least two replicas,
 *     including the local replica</li>
 * <li>`WriteThree` the value will immediately be written to at least three replicas,
 *     including the local replica</li>
 * <li>`WriteTo(n)` the value will immediately be written to at least `n` replicas,
 *     including the local replica</li>
 * <li>`WriteQuorum` the value will immediately be written to a majority of replicas, i.e.
 *     at least `N/2 + 1` replicas, where N is the number of nodes in the cluster
 *     (or cluster role group)</li>
 * <li>`WriteAll` the value will immediately be written to all nodes in the cluster
 *     (or all nodes in the cluster role group)</li>
 * </ul>
 *
 * As reply of the `Update` a [[Replicator.UpdateSuccess]] is sent to the sender of the
 * `Update` if the value was successfully replicated according to the supplied consistency
 * level within the supplied timeout. Otherwise a [[Replicator.UpdateFailure]] is sent.
 * Note that `UpdateFailure` does not mean that the update completely failed or was rolled back.
 * It may still have been replicated to some nodes, and will eventually be replicated to all
 * nodes with the gossip protocol. The data will always converge to the the same value no
 * matter how many times you retry the `Update`.
 *
 * In the `Update` message you must pass an expected sequence number for optimistic concurrency
 * control of local updates. It starts at 0 and is incremented by one for each local update. Each
 * data `key` has its own sequence. If the `seqNo` in the `Update` does not match current sequence
 * number of the `Replicator` the update will be discarded and a [[Replicator.WrongSeqNo]] message
 * is sent back. The reason for requiring a sequence number is to be able to detect concurrent
 * (conflicting) updates from the local node.
 * For example two different actors running in the same `ActorSystem` changing the same data
 * item and sending the updates to the `Replicator` independent of each other. Normally you would
 * serialize the updates via one actor, and then you can maintain a sequence number counter
 * in that actor. Even in that case there is a possibility that you will receive
 * [[Replicator.WrongSeqNo]]. It can happen when the `Replicator` itself modifies the
 * data when pruning history belonging to removed cluster nodes (see below).
 *
 * In the `Update` message you can pass an optional request context, which the `Replicator`
 * does not care about, but it is included in the reply messages. This is a convenient
 * way to pass contextual information (e.g. original sender) without having to use `ask`
 * or local correlation data structures. For example the original command can be passed
 * in a `Update` messages, and retried in case of `WrongSeqNo` failure.
 *
 * To retrieve the current value of a data you send [[Replicator.Get]] message to the
 * `Replicator`. You supply a consistency level which has the following meaning:
 * <ul>
 * <li>`ReadOne` the value will only be read from the local replica</li>
 * <li>`ReadTwo` the value will be read and merged from two replicas,
 *     including the local replica</li>
 * <li>`ReadThree` the value will be read and merged from three replicas,
 *     including the local replica</li>
 * <li>`ReadFrom(n)` the value will be read and merged from `n` replicas,
 *     including the local replica</li>
 * <li>`ReadQuorum` the value will read and merged from a majority of replicas, i.e.
 *     at least `N/2 + 1` replicas, where N is the number of nodes in the cluster
 *     (or cluster role group)</li>
 * <li>`ReadAll` the value will be read and merged from all nodes in the cluster
 *     (or all nodes in the cluster role group)</li>
 * </ul>
 *
 * As reply of the `Get` a [[Replicator.GetSuccess]] is sent to the sender of the
 * `Get` if the value was successfully retrieved according to the supplied consistency
 * level within the supplied timeout. Otherwise a [[Replicator.GetFailure]] is sent.
 * If the key does not exist the reply will be [[Replicator.GetFailure]].
 * You will always read your own writes.
 *
 * In the `Get` message you can pass an optional request context in the same way as for the
 * `Update` message, described above. For example the original sender can be passed and replied
 * to after receiving and transforming `GetSuccess`.
 *
 * You can retrieve all keys of a local replica by sending [[Replicator.GetKeys]] message to the
 * `Replicator`. The reply of `GetKeys` is a [[Replicator.GetKeysResult]] message.
 *
 * You may also register interest in change notifications by sending [[Replicator.Subscribe]]
 * message to the `Replicator`. It will send [[Replicator.Changed]] messages to the registered
 * subscriber when the data for the subscribed key is updated. The subscriber is automatically
 * removed if the subscriber is terminated. A subscriber can also be deregistered with the
 * [[Replicator.Unsubscribe]] message.
 *
 * A data entry can be deleted by sending a [[Replicator.Delete]] message to the local
 * local `Replicator`. As reply of the `Delete` a [[Replicator.DeleteSuccess]] is sent to
 * the sender of the `Delete` if the value was successfully replicated according to the supplied
 * consistency level within the supplied timeout. Otherwise a [[Replicator.ReplicationDeleteFailure]]
 * is sent. Note that `ReplicationDeleteFailure` does not mean that the delete completely failed or
 * was rolled back. It may still have been replicated to some nodes, and may eventually be replicated
 * to all nodes. A deleted key can not be reused again, but it is still recommended to delete unused
 * data entries because that reduces the replication overhead when new nodes join the cluster.
 * Subsequent `Delete`, `Update` and `Get` requests will be replied with [[Replicator.DataDeleted]].
 * Subscribers will receive [[Replicator.DataDeleted]].
 *
 * One thing that can be problematic with CRDTs is that some data types accumulate history (garbage).
 * For example a `GCounter` keeps track of one counter per node. If a `GCounter` has been updated
 * from one node it will associate the identifier of that node forever. That can become a problem
 * for long running systems with many cluster nodes being added and removed. To solve this problem
 * the `Replicator` performs pruning of data associated with nodes that have been removed from the
 * cluster. Data types that need pruning have to implement [[RemovedNodePruning]]. The pruning consists
 * of several steps:
 * <ol>
 * <li>When a node is removed from the cluster it is first important that all updates that were
 * done by that node are disseminated to all other nodes. The pruning will not start until the
 * `maxPruningDissemination` duration has elapsed. The time measurement is stopped when any
 * replica is unreachable, so it should be configured to worst case in a healthy cluster.</li>
 * <li>The nodes are ordered by their address and the node ordered first is called leader.
 * The leader initiates the pruning by adding a `PruningInitialized` marker in the data envelope.
 * This is gossiped to all other nodes and they mark it as seen when they receive it.</li>
 * <li>When the leader sees that all other nodes have seen the `PruningInitialized` marker
 * the leader performs the pruning and changes the marker to `PruningPerformed` so that nobody
 * else will redo the pruning. The data envelope with this pruning state is a CRDT itself.
 * The pruning is typically performed by "moving" the part of the data associated with
 * the removed node to the leader node. For example, a `GCounter` is a `Map` with the node as key
 * and the counts done by that node as value. When pruning the value of the removed node is
 * moved to the entry owned by the leader node. See [[RemovedNodePruning#prune]].</li>
 * <li>Thereafter the data is always cleared from parts associated with the removed node so that
 * it does not come back when merging. See [[RemovedNodePruning#clear]]</li>
 * <li>After another `maxPruningDissemination` duration after pruning the last entry from the
 * removed node the `PruningPerformed` markers in the data envelope are collapsed into a
 * single tombstone entry, for efficiency. Clients may continue to use old data and therefore
 * all data are always cleared from parts associated with tombstoned nodes. </li>
 * </ol>
 */
object Replicator {

  // FIXME docs
  def props(
    role: Option[String],
    gossipInterval: FiniteDuration = 2.second,
    maxDeltaElements: Int = 1000,
    pruningInterval: FiniteDuration = 30.seconds,
    maxPruningDissemination: FiniteDuration = 60.seconds): Props =
    Props(new Replicator(role, gossipInterval, maxDeltaElements, pruningInterval, maxPruningDissemination))

  // FIXME Java API props

  sealed trait ReadConsistency
  object ReadOne extends ReadFrom(1)
  object ReadTwo extends ReadFrom(2)
  object ReadThree extends ReadFrom(3)
  case class ReadFrom(n: Int) extends ReadConsistency
  case object ReadQuorum extends ReadConsistency
  case object ReadAll extends ReadConsistency

  sealed trait WriteConsistency
  object WriteOne extends WriteTo(1)
  object WriteTwo extends WriteTo(2)
  object WriteThree extends WriteTo(3)
  case class WriteTo(n: Int) extends WriteConsistency
  case object WriteQuorum extends WriteConsistency
  case object WriteAll extends WriteConsistency

  // FIXME Java API of nested case objects

  case object GetKeys
  case class GetKeysResult(keys: Set[String]) {
    /**
     * Java API
     */
    def getKeys: java.util.Set[String] = {
      import scala.collection.JavaConverters._
      keys.asJava
    }
  }

  object Get {
    /**
     * `Get` value from local `Replicator`, i.e. `ReadOne`
     * consistency.
     */
    def apply(key: String): Get = Get(key, ReadOne, Duration.Zero, None)
  }
  case class Get(key: String, consistency: ReadConsistency, timeout: FiniteDuration, request: Option[Any] = None)
  case class GetSuccess(key: String, data: ReplicatedData, seqNo: Long, request: Option[Any])
  case class NotFound(key: String, request: Option[Any])
  case class GetFailure(key: String, request: Option[Any])

  case class Subscribe(key: String, subscriber: ActorRef)
  case class Unsubscribe(key: String, subscriber: ActorRef)
  case class Changed(key: String, data: ReplicatedData)

  object Update {
    /**
     * `Update` value of local `Replicator`, i.e. `WriteOne`
     * consistency.
     */
    def apply(key: String, data: ReplicatedData, seqNo: Long): Update =
      Update(key, data, seqNo, WriteOne, Duration.Zero, None)

    /**
     * `Update` value of local `Replicator`, i.e. `WriteOne`
     * consistency.
     */
    def apply(key: String, data: ReplicatedData, seqNo: Long, request: Option[Any]): Update =
      Update(key, data, seqNo, WriteOne, Duration.Zero, None)
  }
  case class Update(key: String, data: ReplicatedData, seqNo: Long,
                    consistency: WriteConsistency, timeout: FiniteDuration, request: Option[Any] = None)
  case class UpdateSuccess(key: String, seqNo: Any, request: Option[Any])
  sealed trait UpdateFailure {
    def key: String
    def seqNo: Long
    def request: Option[Any]
  }
  case class ReplicationUpdateFailure(key: String, seqNo: Long, request: Option[Any]) extends UpdateFailure
  case class ConflictingType(key: String, seqNo: Long, errorMessage: String, request: Option[Any])
    extends RuntimeException with NoStackTrace with UpdateFailure
  case class WrongSeqNo(key: String, currentData: ReplicatedData, seqNo: Long, currentSeqNo: Long,
                        request: Option[Any]) extends RuntimeException with NoStackTrace with UpdateFailure
  case class InvalidUsage(key: String, seqNo: Long, errorMessage: String, request: Option[Any])
    extends RuntimeException with NoStackTrace with UpdateFailure

  object Delete {
    /**
     * `Delete` value of local `Replicator`, i.e. `WriteOne`
     * consistency.
     */
    def apply(key: String): Delete = Delete(key, WriteOne, Duration.Zero)
  }
  case class Delete(key: String, consistency: WriteConsistency, timeout: FiniteDuration)
  case class DeleteSuccess(key: String)
  case class ReplicationDeleteFailure(key: String)
  case class DataDeleted(key: String)
    extends RuntimeException with NoStackTrace

  /**
   * INTERNAL API
   */
  private[akka] object Internal {

    case object GossipTick
    case object RemovedNodePruningTick
    case object ClockTick
    case class Write(key: String, envelope: DataEnvelope)
    case object WriteAck
    case class Read(key: String)
    case class ReadResult(envelope: Option[DataEnvelope])
    case class ReadRepair(key: String, envelope: DataEnvelope)

    // Gossip Status message contains MD5 digests of the data to determine when
    // to send the full data
    type Digest = ByteString
    val deletedDigest: Digest = ByteString.empty

    case class DataEnvelope(data: ReplicatedData,
                            pruning: Map[UniqueAddress, PruningState] = Map.empty) {

      import PruningState._

      def initRemovedNodePruning(removed: UniqueAddress, owner: UniqueAddress): DataEnvelope = {
        copy(pruning = pruning.updated(removed, PruningState(owner, PruningInitialized(Set.empty))))
      }

      def prune(from: UniqueAddress): DataEnvelope = {
        data match {
          case dataWithRemovedNodePruning: RemovedNodePruning ⇒
            require(pruning.contains(from))
            val to = pruning(from).owner
            val prunedData = dataWithRemovedNodePruning.prune(from, to)
            copy(data = prunedData, pruning = pruning.updated(from, PruningState(to, PruningPerformed)))
          case _ ⇒ this
        }

      }

      def merge(other: DataEnvelope): DataEnvelope =
        if (other.data == DeletedData) DeletedEnvelope
        else {
          var mergedRemovedNodePruning = other.pruning
          for ((key, thisValue) ← pruning) {
            mergedRemovedNodePruning.get(key) match {
              case None ⇒ mergedRemovedNodePruning = mergedRemovedNodePruning.updated(key, thisValue)
              case Some(thatValue) ⇒
                mergedRemovedNodePruning = mergedRemovedNodePruning.updated(key, thisValue merge thatValue)
            }
          }

          copy(data = cleaned(data, mergedRemovedNodePruning), pruning = mergedRemovedNodePruning).merge(other.data)
        }

      def merge(otherData: ReplicatedData): DataEnvelope =
        if (otherData == DeletedData) DeletedEnvelope
        else copy(data = data merge cleaned(otherData, pruning).asInstanceOf[data.T])

      private def cleaned(c: ReplicatedData, p: Map[UniqueAddress, PruningState]): ReplicatedData = p.foldLeft(c) {
        case (c: RemovedNodePruning, (removed, PruningState(_, PruningPerformed))) ⇒
          if (c.hasDataFrom(removed)) c.clear(removed) else c
        case (c, _) ⇒ c
      }

      def addSeen(node: Address): DataEnvelope = {
        var changed = false
        val newRemovedNodePruning = pruning.map {
          case (removed, pruningNode) ⇒
            val newPruningState = pruningNode.addSeen(node)
            changed = (newPruningState ne pruningNode) || changed
            (removed, newPruningState)
        }
        if (changed) copy(pruning = newRemovedNodePruning)
        else this
      }
    }

    val DeletedEnvelope = DataEnvelope(DeletedData)

    case object DeletedData extends ReplicatedData {
      type T = ReplicatedData
      override def merge(that: ReplicatedData): ReplicatedData = DeletedData
    }

    case class Status(digests: Map[String, Digest])
    case class Gossip(updatedData: Map[String, DataEnvelope])

    // Testing purpose
    case object GetNodeCount
    case class NodeCount(n: Int)
  }
}

/**
 * A replicated in-memory data store, described in
 * [[Replicator$ Replicator companion object]].
 *
 * @see [[Replicator$ Replicator companion object]]
 */
class Replicator(
  role: Option[String],
  gossipInterval: FiniteDuration,
  maxDeltaElements: Int,
  pruningInterval: FiniteDuration,
  maxPruningDissemination: FiniteDuration) extends Actor with ActorLogging {

  import Replicator._
  import Replicator.Internal._
  import PruningState._

  val cluster = Cluster(context.system)
  val selfAddress = cluster.selfAddress
  val selfUniqueAddress = cluster.selfUniqueAddress

  require(!cluster.isTerminated, "Cluster node must not be terminated")
  require(role.forall(cluster.selfRoles.contains),
    s"This cluster member [${selfAddress}] doesn't have the role [$role]")

  //Start periodic gossip to random nodes in cluster
  import context.dispatcher
  val gossipTask = context.system.scheduler.schedule(gossipInterval, gossipInterval, self, GossipTick)
  val pruningTask = context.system.scheduler.schedule(gossipInterval, gossipInterval, self, RemovedNodePruningTick)
  val clockTask = context.system.scheduler.schedule(gossipInterval, gossipInterval, self, ClockTick)

  val serializer = SerializationExtension(context.system).serializerFor(classOf[DataEnvelope])
  val maxPruningDisseminationNanos = maxPruningDissemination.toNanos

  // cluster nodes, doesn't contain selfAddress
  var nodes: Set[Address] = Set.empty

  // nodes removed from cluster, to be pruned, and tombstoned
  var removedNodes: Map[UniqueAddress, Long] = Map.empty
  var pruningPerformed: Map[UniqueAddress, Long] = Map.empty
  var tombstoneNodes: Set[UniqueAddress] = Set.empty

  var leader: Option[Address] = None
  def isLeader: Boolean = leader.exists(_ == selfAddress)

  // for pruning timeouts are based on clock that is only increased when all
  // nodes are reachable
  var previousClockTime = System.nanoTime()
  var allReachableClockTime = 0L
  var unreachable = Set.empty[Address]

  var dataEntries = Map.empty[String, (DataEnvelope, Digest)]
  var seqNumbers = Map.empty[String, Long].withDefaultValue(0L)

  var subscribers = Map.empty[String, Set[ActorRef]]

  override def preStart(): Unit = {
    val leaderChangedClass = if (role.isDefined) classOf[RoleLeaderChanged] else classOf[LeaderChanged]
    cluster.subscribe(self, initialStateMode = InitialStateAsEvents,
      classOf[MemberEvent], classOf[ReachabilityEvent], leaderChangedClass)
  }

  override def postStop(): Unit = {
    cluster unsubscribe self
    gossipTask.cancel()
    pruningTask.cancel()
    clockTask.cancel()
  }

  def matchingRole(m: Member): Boolean = role.forall(m.hasRole)

  def receive = {
    case Get(key, consistency, timeout, req) ⇒ receiveGet(key, consistency, timeout, req)
    case Read(key) ⇒ receiveRead(key)
    case Update(key, _, seqNo, _, _, req) if !isLocalSender ⇒ receiveInvalidUpdate(key, seqNo, req)
    case Update(key, data, seqNo, consistency, timeout, req) ⇒ receiveUpdate(key, data, seqNo, consistency, timeout, req)
    case Write(key, envelope) ⇒ receiveWrite(key, envelope)
    case ReadRepair(key, envelope) ⇒ write(key, envelope)
    case GetKeys ⇒ receiveGetKeys()
    case Delete(key, consistency, timeout) ⇒ receiveDelete(key, consistency, timeout)
    case GossipTick ⇒ receiveGossipTick()
    case Status(otherDigests) ⇒ receiveStatus(otherDigests)
    case Gossip(updatedData) ⇒ receiveGossip(updatedData)
    case Subscribe(key, subscriber) ⇒ receiveSubscribe(key, subscriber)
    case Unsubscribe(key, subscriber) ⇒ receiveUnsubscribe(key, subscriber)
    case Terminated(ref) ⇒ receiveTerminated(ref)
    case MemberUp(m) ⇒ receiveMemberUp(m)
    case MemberRemoved(m, _) ⇒ receiveMemberRemoved(m)
    case _: MemberEvent ⇒ // not of interest
    case UnreachableMember(m) ⇒ receiveUnreachable(m)
    case ReachableMember(m) ⇒ receiveReachable(m)
    case LeaderChanged(leader) ⇒ receiveLeaderChanged(leader, None)
    case RoleLeaderChanged(role, leader) ⇒ receiveLeaderChanged(leader, Some(role))
    case ClockTick ⇒ receiveClockTick()
    case RemovedNodePruningTick ⇒ receiveRemovedNodePruningTick()
    case GetNodeCount ⇒ receiveGetNodeCount()
  }

  def receiveGet(key: String, consistency: ReadConsistency, timeout: FiniteDuration, req: Option[Any]): Unit = {
    val localValue = getData(key)
    if (consistency == ReadOne) {
      val reply = localValue match {
        case Some(DataEnvelope(DeletedData, _)) ⇒ DataDeleted(key)
        case Some(DataEnvelope(data, _))        ⇒ GetSuccess(key, data, seqNumbers(key), req)
        case None                               ⇒ NotFound(key, req)
      }
      sender() ! reply
    } else
      context.actorOf(Props(classOf[ReadAggregator], key, consistency, timeout, req, nodes, localValue,
        seqNumbers.get(key), sender()))
  }

  def receiveRead(key: String): Unit = {
    sender() ! ReadResult(getData(key))
  }

  def isLocalSender: Boolean = !sender().path.address.hasGlobalScope

  def receiveInvalidUpdate(key: String, seqNo: Long, req: Option[Any]): Unit = {
    sender() ! InvalidUsage(key, seqNo,
      "Replicator Update should only be used from an actor running in same local ActorSystem", req)
  }

  def receiveUpdate(key: String, data: ReplicatedData, seqNo: Long, consistency: WriteConsistency,
                    timeout: FiniteDuration, req: Option[Any]): Unit = {
    if (log.isDebugEnabled) log.debug("Received Update {} existing {}", data, getData(key))
    update(key, data, seqNo, req) match {
      case Success(merged) ⇒
        if (consistency == WriteOne)
          sender() ! UpdateSuccess(key, seqNo, req)
        else
          context.actorOf(Props(classOf[WriteAggregator], key, merged, seqNo, consistency, timeout, req,
            nodes, sender()))
      case Failure(e) ⇒
        sender() ! e
    }
  }

  def update(key: String, data: ReplicatedData, seqNo: Long, req: Option[Any]): Try[DataEnvelope] = {
    getData(key) match {
      case Some(DataEnvelope(DeletedData, _)) ⇒
        Failure(DataDeleted(key))
      case Some(envelope @ DataEnvelope(existing, pruning)) ⇒
        if (existing.getClass == data.getClass || data == DeletedData) {
          val currentSeqNo = seqNumbers(key)
          if (currentSeqNo == seqNo) {
            seqNumbers = seqNumbers.updated(key, currentSeqNo + 1)
            val merged = envelope.merge(clearTombstoned(data))
            setData(key, merged)
            Success(merged)
          } else
            Failure(WrongSeqNo(key, existing, seqNo, currentSeqNo, req))
        } else {
          val errMsg = s"Wrong type for updating [$key], existing type [${existing.getClass.getName}], got [${data.getClass.getName}]"
          log.warning(errMsg)
          Failure(ConflictingType(key, seqNo, errMsg, req))
        }
      case None ⇒
        seqNumbers = seqNumbers.updated(key, 1L)
        val envelope = DataEnvelope(clearTombstoned(data))
        setData(key, envelope)
        Success(envelope)
    }
  }

  def receiveWrite(key: String, envelope: DataEnvelope): Unit = {
    write(key, envelope)
    sender() ! WriteAck
  }

  def write(key: String, writeEnvelope: DataEnvelope): Unit =
    getData(key) match {
      case Some(DataEnvelope(DeletedData, _)) ⇒ // already deleted
      case Some(envelope @ DataEnvelope(existing, _)) ⇒
        if (existing.getClass == writeEnvelope.data.getClass || writeEnvelope.data == DeletedData) {
          val merged = envelope.merge(clearTombstoned(writeEnvelope)).addSeen(selfAddress)
          setData(key, merged)
        } else {
          log.warning("Wrong type for writing [{}], existing type [{}], got [{}]",
            key, existing.getClass.getName, writeEnvelope.data.getClass.getName)
        }
      case None ⇒
        setData(key, clearTombstoned(writeEnvelope).addSeen(selfAddress))
    }

  def receiveGetKeys(): Unit =
    sender() ! GetKeysResult(dataEntries.collect {
      case (key, (DataEnvelope(data, _), _)) if data != DeletedData ⇒ key
    }(collection.breakOut))

  def receiveDelete(key: String, consistency: WriteConsistency,
                    timeout: FiniteDuration): Unit = {
    val seqNo = seqNumbers.getOrElse(key, 0L)
    update(key, DeletedData, seqNo, None) match {
      case Success(merged) ⇒
        if (consistency == WriteOne)
          sender() ! DeleteSuccess(key)
        else
          context.actorOf(Props(classOf[WriteAggregator], key, merged, seqNo, consistency, timeout, None,
            nodes, sender()))
      case Failure(e) ⇒
        sender() ! e
    }
  }

  def setData(key: String, envelope: DataEnvelope): Unit = {
    val digest =
      if (envelope.data == DeletedData) deletedDigest
      else {
        val bytes = serializer.toBinary(envelope)
        ByteString.fromArray(MessageDigest.getInstance("MD5").digest(bytes))
      }

    // notify subscribers, when changed
    subscribers.get(key) foreach { s ⇒
      val oldDigest = getDigest(key)
      if (oldDigest.isEmpty || digest != oldDigest.get) {
        val msg = if (envelope.data == DeletedData) DataDeleted(key) else Changed(key, envelope.data)
        s foreach { _ ! msg }
      }
    }

    dataEntries = dataEntries.updated(key, (envelope, digest))
  }

  def getData(key: String): Option[DataEnvelope] = dataEntries.get(key).map { case (envelope, _) ⇒ envelope }

  def getDigest(key: String): Option[Digest] = dataEntries.get(key).map { case (_, digest) ⇒ digest }

  def receiveGossipTick(): Unit = selectRandomNode(nodes.toVector) foreach gossipTo

  def gossipTo(address: Address): Unit =
    replica(address) ! Status(dataEntries.map { case (key, (_, digest)) ⇒ (key, digest) })

  def selectRandomNode(addresses: immutable.IndexedSeq[Address]): Option[Address] =
    if (addresses.isEmpty) None else Some(addresses(ThreadLocalRandom.current nextInt addresses.size))

  def replica(address: Address): ActorSelection =
    context.actorSelection(self.path.toStringWithAddress(address))

  def receiveStatus(otherDigests: Map[String, Digest]): Unit = {
    if (log.isDebugEnabled)
      log.debug("Received gossip status from [{}], containing [{}]", sender().path.address,
        otherDigests.keys.mkString(", "))

    def isOtherOutdated(key: String, otherDigest: Digest): Boolean =
      getDigest(key) match {
        case Some(digest) if digest != otherDigest ⇒ true
        case _                                     ⇒ false
      }
    val otherOutdatedKeys = otherDigests.collect {
      case (key, otherV) if isOtherOutdated(key, otherV) ⇒ key
    }
    val otherMissingKeys = dataEntries.keySet -- otherDigests.keySet
    val keys = (otherMissingKeys ++ otherOutdatedKeys).take(maxDeltaElements)
    if (keys.nonEmpty) {
      if (log.isDebugEnabled)
        log.debug("Sending gossip to [{}], containing [{}]", sender().path.address,
          keys.mkString(", "))
      val g = Gossip(keys.map(k ⇒ k -> getData(k).get)(collection.breakOut))
      sender() ! g
    }
  }

  def receiveGossip(updatedData: Map[String, DataEnvelope]): Unit = {
    if (log.isDebugEnabled)
      log.debug("Received gossip from [{}], containing [{}]", sender().path.address, updatedData.keys.mkString(", "))
    updatedData.foreach {
      case (key, envelope) ⇒ write(key, envelope)
    }
  }

  def receiveSubscribe(key: String, subscriber: ActorRef): Unit = {
    subscribers = subscribers.updated(key, subscribers.getOrElse(key, Set.empty) + subscriber)
    context.watch(subscriber)
    getData(key) foreach {
      case DataEnvelope(DeletedData, _) ⇒ subscriber ! DataDeleted(key)
      case DataEnvelope(data, _)        ⇒ subscriber ! Changed(key, data)
    }
  }

  def receiveUnsubscribe(key: String, subscriber: ActorRef): Unit = {
    subscribers.get(key) match {
      case Some(existing) ⇒
        val s = existing - subscriber
        if (s.isEmpty)
          subscribers -= key
        else
          subscribers = subscribers.updated(key, s)
      case None ⇒
    }
    if (!hasSubscriber(subscriber))
      context.unwatch(subscriber)
  }

  def hasSubscriber(subscriber: ActorRef): Boolean = {
    @tailrec def find(keys: List[String]): Boolean =
      if (keys.isEmpty) false
      else if (subscribers(keys.head)(subscriber)) true
      else find(keys.tail)

    find(subscribers.keys.toList)
  }

  def receiveTerminated(ref: ActorRef): Unit = {
    for ((k, s) ← subscribers) {
      if (s(ref)) {
        if (s.isEmpty)
          subscribers -= k
        else
          subscribers = subscribers.updated(k, s)
      }
    }
  }

  def receiveMemberUp(m: Member): Unit =
    if (matchingRole(m) && m.address != selfAddress)
      nodes += m.address

  def receiveMemberRemoved(m: Member): Unit = {
    if (m.address == selfAddress)
      context stop self
    else if (matchingRole(m)) {
      nodes -= m.address
      removedNodes = removedNodes.updated(m.uniqueAddress, allReachableClockTime)
      unreachable -= m.address
    }
  }

  def receiveUnreachable(m: Member): Unit =
    if (matchingRole(m)) unreachable += m.address

  def receiveReachable(m: Member): Unit =
    if (matchingRole(m)) unreachable -= m.address

  def receiveLeaderChanged(leaderOption: Option[Address], roleOption: Option[String]): Unit =
    if (roleOption == role) leader = leaderOption

  def receiveClockTick(): Unit = {
    val now = System.nanoTime()
    if (unreachable.isEmpty)
      allReachableClockTime += (now - previousClockTime)
    previousClockTime = now
  }

  def receiveRemovedNodePruningTick(): Unit = {
    if (isLeader && removedNodes.nonEmpty) {
      initRemovedNodePruning()
    }
    performRemovedNodePruning()
    tombstoneRemovedNodePruning()
  }

  def initRemovedNodePruning(): Unit = {
    // initiate pruning for removed nodes
    val removedSet: Set[UniqueAddress] = removedNodes.collect {
      case (r, t) if ((allReachableClockTime - t) > maxPruningDisseminationNanos) ⇒ r
    }(collection.breakOut)

    for ((key, (envelope, _)) ← dataEntries; removed ← removedSet) {

      def init(): Unit = {
        val newEnvelope = envelope.initRemovedNodePruning(removed, selfUniqueAddress)
        log.debug("Initiated pruning of [{}] for data key [{}]", removed, key)
        setData(key, newEnvelope)
        // no need to update seqNo here since we have not touched the data
      }

      envelope.data match {
        case dataWithRemovedNodePruning: RemovedNodePruning ⇒
          envelope.pruning.get(removed) match {
            case None ⇒ init()
            case Some(PruningState(owner, PruningInitialized(_))) if owner != selfUniqueAddress ⇒ init()
            case _ ⇒ // already in progress
          }
        case _ ⇒
      }
    }
  }

  def performRemovedNodePruning(): Unit = {
    // perform pruning when all seen Init
    dataEntries.foreach {
      case (key, (envelope @ DataEnvelope(data: RemovedNodePruning, pruning), _)) ⇒
        pruning.foreach {
          case (removed, PruningState(owner, PruningInitialized(seen))) if owner == selfUniqueAddress && seen == nodes ⇒
            val newEnvelope = envelope.prune(removed)
            pruningPerformed = pruningPerformed.updated(removed, allReachableClockTime)
            log.debug("Perform pruning of [{}] from [{}] to [{}]", key, removed, selfUniqueAddress)
            setData(key, newEnvelope)
            seqNumbers = seqNumbers.updated(key, seqNumbers(key) + 1)
          case _ ⇒
        }
      case _ ⇒ // deleted, or pruning not needed
    }
  }

  def tombstoneRemovedNodePruning(): Unit = {

    def allPruningPerformed(removed: UniqueAddress): Boolean = {
      dataEntries.forall {
        case (key, (envelope @ DataEnvelope(data: RemovedNodePruning, pruning), _)) ⇒
          pruning.get(removed) match {
            case Some(PruningState(_, PruningInitialized(_))) ⇒ false
            case _ ⇒ true
          }
        case _ ⇒ true // deleted, or pruning not needed
      }
    }

    pruningPerformed.foreach {
      case (removed, timestamp) if ((allReachableClockTime - timestamp) > maxPruningDisseminationNanos) &&
        allPruningPerformed(removed) ⇒
        log.debug("All pruning performed for [{}], tombstoned", removed)
        pruningPerformed -= removed
        removedNodes -= removed
        tombstoneNodes += removed
        dataEntries.foreach {
          case (key, (envelope @ DataEnvelope(data: RemovedNodePruning, _), _)) ⇒
            setData(key, clearTombstoned(removed, envelope))
          case _ ⇒ // deleted, or pruning not needed
        }
      case (removed, timestamp) ⇒ // not ready
    }
  }

  def clearTombstoned(envelope: DataEnvelope): DataEnvelope =
    tombstoneNodes.foldLeft(envelope)((c, removed) ⇒ clearTombstoned(removed, c))

  def clearTombstoned(removed: UniqueAddress, envelope: DataEnvelope): DataEnvelope = {
    val cleared = clearTombstoned(removed, envelope.data)
    if ((cleared ne envelope.data) || envelope.pruning.contains(removed))
      envelope.copy(data = cleared, pruning = envelope.pruning - removed)
    else
      envelope
  }

  def clearTombstoned(data: ReplicatedData): ReplicatedData =
    tombstoneNodes.foldLeft(data)((c, removed) ⇒ clearTombstoned(removed, c))

  def clearTombstoned(removed: UniqueAddress, data: ReplicatedData): ReplicatedData =
    data match {
      case dataWithRemovedNodePruning: RemovedNodePruning ⇒
        if (dataWithRemovedNodePruning.hasDataFrom(removed)) dataWithRemovedNodePruning.clear(removed) else data
      case _ ⇒ data
    }

  def receiveGetNodeCount(): Unit = {
    // selfAddress is not included in the set
    sender() ! NodeCount(nodes.size + 1)
  }

}

/**
 * INTERNAL API
 */
private[akka] abstract class ReadWriteAggregator extends Actor {
  import Replicator.Internal._

  def timeout: FiniteDuration
  def nodes: Set[Address]

  import context.dispatcher
  var timeoutSchedule = context.system.scheduler.scheduleOnce(timeout, self, ReceiveTimeout)

  var remaining = nodes

  override def postStop(): Unit = {
    timeoutSchedule.cancel()
  }

  def replica(address: Address): ActorSelection =
    context.actorSelection(context.parent.path.toStringWithAddress(address))

  def becomeDone(): Unit = {
    if (remaining.isEmpty)
      context.stop(self)
    else {
      // stay around a bit more to collect acks, avoiding deadletters
      context.become(done)
      timeoutSchedule.cancel()
      timeoutSchedule = context.system.scheduler.scheduleOnce(2.seconds, self, ReceiveTimeout)
    }
  }

  def done: Receive = {
    case WriteAck | _: ReadResult ⇒
      remaining -= sender().path.address
      if (remaining.isEmpty) context.stop(self)
    case ReceiveTimeout ⇒ context.stop(self)
  }
}

/**
 * INTERNAL API
 */
private[akka] class WriteAggregator(
  key: String,
  envelope: Replicator.Internal.DataEnvelope,
  seqNo: Long,
  consistency: Replicator.WriteConsistency,
  override val timeout: FiniteDuration,
  req: Option[Any],
  override val nodes: Set[Address],
  replyTo: ActorRef) extends ReadWriteAggregator {

  import Replicator._
  import Replicator.Internal._

  val doneWhenRemainingSize = consistency match {
    case WriteTo(n) ⇒ nodes.size - (n - 1)
    case WriteAll   ⇒ 0
    case WriteQuorum ⇒
      val N = nodes.size + 1
      if (N < 3) -1
      else {
        val w = N / 2 + 1 // write to at least (N/2+1) nodes
        N - w
      }
  }

  override def preStart(): Unit = {
    // FIXME perhaps not send to all, e.g. for WriteTwo we could start with less
    val writeMsg = Write(key, envelope)
    nodes.foreach { replica(_) ! writeMsg }

    if (remaining.size == doneWhenRemainingSize)
      reply(ok = true)
    else if (doneWhenRemainingSize < 0 || remaining.size < doneWhenRemainingSize)
      reply(ok = false)
  }

  def receive = {
    case WriteAck ⇒
      remaining -= sender().path.address
      if (remaining.size == doneWhenRemainingSize)
        reply(ok = true)
    case ReceiveTimeout ⇒ reply(ok = false)
  }

  def reply(ok: Boolean): Unit = {
    if (ok && envelope.data == DeletedData)
      replyTo.tell(DeleteSuccess(key), context.parent)
    else if (ok)
      replyTo.tell(UpdateSuccess(key, seqNo, req), context.parent)
    else if (envelope.data == DeletedData)
      replyTo.tell(ReplicationDeleteFailure(key), context.parent)
    else
      replyTo.tell(ReplicationUpdateFailure(key, seqNo, req), context.parent)
    becomeDone()
  }
}

/**
 * INTERNAL API
 */
private[akka] class ReadAggregator(
  key: String,
  consistency: Replicator.ReadConsistency,
  override val timeout: FiniteDuration,
  req: Option[Any],
  override val nodes: Set[Address],
  localValue: Option[Replicator.Internal.DataEnvelope],
  localSeqNo: Option[Long],
  replyTo: ActorRef) extends ReadWriteAggregator {

  import Replicator._
  import Replicator.Internal._

  var result = localValue
  val doneWhenRemainingSize = consistency match {
    case ReadFrom(n) ⇒ nodes.size - (n - 1)
    case ReadAll     ⇒ 0
    case ReadQuorum ⇒
      val N = nodes.size + 1
      if (N < 3) -1
      else {
        val r = N / 2 + 1 // read from at least (N/2+1) nodes
        N - r
      }
  }

  override def preStart(): Unit = {
    // FIXME perhaps not send to all, e.g. for ReadTwo we could start with less
    val readMsg = Read(key)
    nodes.foreach { replica(_) ! readMsg }

    if (remaining.size == doneWhenRemainingSize)
      reply(ok = true)
    else if (doneWhenRemainingSize < 0 || remaining.size < doneWhenRemainingSize)
      reply(ok = false)
  }

  def receive = {
    case ReadResult(envelope) ⇒
      result = (result, envelope) match {
        case (Some(a), Some(b))  ⇒ Some(a.merge(b))
        case (r @ Some(_), None) ⇒ r
        case (None, r @ Some(_)) ⇒ r
        case (None, None)        ⇒ None
      }
      remaining -= sender().path.address
      if (remaining.size == doneWhenRemainingSize)
        reply(ok = true)
    case ReceiveTimeout ⇒ reply(ok = false)
  }

  def reply(ok: Boolean): Unit = {
    val replyMsg = (ok, result) match {
      case (true, Some(envelope)) ⇒
        context.parent ! ReadRepair(key, envelope)
        if (envelope.data == DeletedData) DataDeleted(key)
        else GetSuccess(key, envelope.data, localSeqNo.getOrElse(0L), req)
      case (true, None) ⇒ NotFound(key, req)
      case (false, _)   ⇒ GetFailure(key, req)
    }
    replyTo.tell(replyMsg, context.parent)
    becomeDone()
  }
}

/*
More TODO
- Java API
- additional documentation
- protobuf
*/ 