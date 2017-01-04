/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster

import scala.collection.immutable
import scala.collection.breakOut

/**
 * INTERNAL API
 */
private[cluster] object Reachability {
  val empty = new Reachability(Vector.empty, Map.empty)

  def apply(records: immutable.IndexedSeq[Record], versions: Map[UniqueAddress, Long]): Reachability =
    new Reachability(records, versions)

  def create(records: immutable.Seq[Record], versions: Map[UniqueAddress, Long]): Reachability = records match {
    case r: immutable.IndexedSeq[Record] ⇒ apply(r, versions)
    case _                               ⇒ apply(records.toVector, versions)
  }

  @SerialVersionUID(1L)
  final case class Record(observer: UniqueAddress, subject: UniqueAddress, status: ReachabilityStatus, version: Long)

  sealed trait ReachabilityStatus
  @SerialVersionUID(1L) case object Reachable extends ReachabilityStatus
  @SerialVersionUID(1L) case object Unreachable extends ReachabilityStatus
  @SerialVersionUID(1L) case object Terminated extends ReachabilityStatus

}

/**
 * INTERNAL API
 *
 * Immutable data structure that holds the reachability status of subject nodes as seen
 * from observer nodes. Failure detector for the subject nodes exist on the
 * observer nodes. Changes (reachable, unreachable, terminated) are only performed
 * by observer nodes to its own records. Each change bumps the version number of the
 * record, and thereby it is always possible to determine which record is newest when
 * merging two instances.
 *
 * Aggregated status of a subject node is defined as (in this order):
 * - Terminated if any observer node considers it as Terminated
 * - Unreachable if any observer node considers it as Unreachable
 * - Reachable otherwise, i.e. no observer node considers it as Unreachable
 */
@SerialVersionUID(1L)
private[cluster] class Reachability private (
  val records:  immutable.IndexedSeq[Reachability.Record],
  val versions: Map[UniqueAddress, Long]) extends Serializable {

  import Reachability._

  private class Cache {
    val (observerRowsMap, allUnreachable, allTerminated) = {
      if (records.isEmpty) {
        val observerRowsMap = Map.empty[UniqueAddress, Map[UniqueAddress, Reachability.Record]]
        val allTerminated = Set.empty[UniqueAddress]
        val allUnreachable = Set.empty[UniqueAddress]
        (observerRowsMap, allUnreachable, allTerminated)
      } else {
        val mapBuilder = scala.collection.mutable.Map.empty[UniqueAddress, Map[UniqueAddress, Reachability.Record]]
        import scala.collection.mutable.SetBuilder
        val terminatedBuilder = new SetBuilder[UniqueAddress, Set[UniqueAddress]](Set.empty)
        val unreachableBuilder = new SetBuilder[UniqueAddress, Set[UniqueAddress]](Set.empty)

        records foreach { r ⇒
          val m = mapBuilder.get(r.observer) match {
            case None    ⇒ Map(r.subject → r)
            case Some(m) ⇒ m.updated(r.subject, r)
          }
          mapBuilder += (r.observer → m)

          if (r.status == Unreachable) unreachableBuilder += r.subject
          else if (r.status == Terminated) terminatedBuilder += r.subject
        }

        val observerRowsMap: Map[UniqueAddress, Map[UniqueAddress, Reachability.Record]] = mapBuilder.toMap
        val allTerminated: Set[UniqueAddress] = terminatedBuilder.result()
        val allUnreachable: Set[UniqueAddress] = unreachableBuilder.result() diff allTerminated

        (observerRowsMap, allUnreachable, allTerminated)
      }
    }

    val allUnreachableOrTerminated: Set[UniqueAddress] =
      if (allTerminated.isEmpty) allUnreachable
      else allUnreachable union allTerminated

  }

  @transient private lazy val cache = new Cache

  private def observerRows(observer: UniqueAddress): Option[Map[UniqueAddress, Reachability.Record]] =
    cache.observerRowsMap.get(observer)

  def unreachable(observer: UniqueAddress, subject: UniqueAddress): Reachability =
    change(observer, subject, Unreachable)

  def reachable(observer: UniqueAddress, subject: UniqueAddress): Reachability =
    change(observer, subject, Reachable)

  def terminated(observer: UniqueAddress, subject: UniqueAddress): Reachability =
    change(observer, subject, Terminated)

  private def currentVersion(observer: UniqueAddress): Long = versions.get(observer) match {
    case None    ⇒ 0
    case Some(v) ⇒ v
  }

  private def nextVersion(observer: UniqueAddress): Long = currentVersion(observer) + 1

  private def change(observer: UniqueAddress, subject: UniqueAddress, status: ReachabilityStatus): Reachability = {
    val v = nextVersion(observer)
    val newVersions = versions.updated(observer, v)
    val newRecord = Record(observer, subject, status, v)
    observerRows(observer) match {
      case None if status == Reachable ⇒ this
      case None ⇒
        new Reachability(records :+ newRecord, newVersions)

      case Some(oldObserverRows) ⇒

        oldObserverRows.get(subject) match {
          case None ⇒
            if (status == Reachable && oldObserverRows.forall { case (_, r) ⇒ r.status == Reachable }) {
              // all Reachable, prune by removing the records of the observer, and bump the version
              new Reachability(records.filterNot(_.observer == observer), newVersions)
            } else
              new Reachability(records :+ newRecord, newVersions)
          case Some(oldRecord) ⇒
            if (oldRecord.status == Terminated || oldRecord.status == status)
              this
            else {
              if (status == Reachable && oldObserverRows.forall { case (_, r) ⇒ r.status == Reachable || r.subject == subject }) {
                // all Reachable, prune by removing the records of the observer, and bump the version
                new Reachability(records.filterNot(_.observer == observer), newVersions)
              } else {
                val newRecords = records.updated(records.indexOf(oldRecord), newRecord)
                new Reachability(newRecords, newVersions)
              }
            }
        }
    }
  }

  def merge(allowed: immutable.Set[UniqueAddress], other: Reachability): Reachability = {
    val recordBuilder = new immutable.VectorBuilder[Record]
    recordBuilder.sizeHint(math.max(this.records.size, other.records.size))
    var newVersions = versions
    allowed foreach { observer ⇒
      val observerVersion1 = this.currentVersion(observer)
      val observerVersion2 = other.currentVersion(observer)

      (this.observerRows(observer), other.observerRows(observer)) match {
        case (None, None) ⇒
        case (Some(rows1), Some(rows2)) ⇒
          val rows = if (observerVersion1 > observerVersion2) rows1 else rows2
          recordBuilder ++= rows.collect { case (_, r) if allowed(r.subject) ⇒ r }
        case (Some(rows1), None) ⇒
          if (observerVersion1 > observerVersion2)
            recordBuilder ++= rows1.collect { case (_, r) if allowed(r.subject) ⇒ r }
        case (None, Some(rows2)) ⇒
          if (observerVersion2 > observerVersion1)
            recordBuilder ++= rows2.collect { case (_, r) if allowed(r.subject) ⇒ r }
      }

      if (observerVersion2 > observerVersion1)
        newVersions += (observer → observerVersion2)
    }

    newVersions = newVersions.filterNot { case (k, _) ⇒ !allowed(k) }

    new Reachability(recordBuilder.result(), newVersions)
  }

  def remove(nodes: Iterable[UniqueAddress]): Reachability = {
    val nodesSet = nodes.to[immutable.HashSet]
    val newRecords = records.filterNot(r ⇒ nodesSet(r.observer) || nodesSet(r.subject))
    val newVersions = versions -- nodes
    Reachability(newRecords, newVersions)
  }

  def removeObservers(nodes: Set[UniqueAddress]): Reachability =
    if (nodes.isEmpty)
      this
    else {
      val newRecords = records.filterNot(r ⇒ nodes(r.observer))
      val newVersions = versions -- nodes
      Reachability(newRecords, newVersions)
    }

  def status(observer: UniqueAddress, subject: UniqueAddress): ReachabilityStatus =
    observerRows(observer) match {
      case None ⇒ Reachable
      case Some(observerRows) ⇒ observerRows.get(subject) match {
        case None         ⇒ Reachable
        case Some(record) ⇒ record.status
      }
    }

  def status(node: UniqueAddress): ReachabilityStatus =
    if (cache.allTerminated(node)) Terminated
    else if (cache.allUnreachable(node)) Unreachable
    else Reachable

  def isReachable(node: UniqueAddress): Boolean = isAllReachable || !allUnreachableOrTerminated.contains(node)

  def isReachable(observer: UniqueAddress, subject: UniqueAddress): Boolean =
    status(observer, subject) == Reachable

  def isAllReachable: Boolean = records.isEmpty

  /**
   * Doesn't include terminated
   */
  def allUnreachable: Set[UniqueAddress] = cache.allUnreachable

  def allUnreachableOrTerminated: Set[UniqueAddress] = cache.allUnreachableOrTerminated

  /**
   * Doesn't include terminated
   */
  def allUnreachableFrom(observer: UniqueAddress): Set[UniqueAddress] =
    observerRows(observer) match {
      case None ⇒ Set.empty
      case Some(observerRows) ⇒
        observerRows.collect {
          case (subject, record) if record.status == Unreachable ⇒ subject
        }(breakOut)
    }

  def observersGroupedByUnreachable: Map[UniqueAddress, Set[UniqueAddress]] = {
    records.groupBy(_.subject).collect {
      case (subject, records) if records.exists(_.status == Unreachable) ⇒
        val observers: Set[UniqueAddress] =
          records.collect { case r if r.status == Unreachable ⇒ r.observer }(breakOut)
        (subject → observers)
    }
  }

  def allObservers: Set[UniqueAddress] = versions.keySet

  def recordsFrom(observer: UniqueAddress): immutable.IndexedSeq[Record] = {
    observerRows(observer) match {
      case None       ⇒ Vector.empty
      case Some(rows) ⇒ rows.valuesIterator.toVector
    }
  }

  // only used for testing
  override def hashCode: Int = versions.hashCode

  // only used for testing
  override def equals(obj: Any): Boolean = obj match {
    case other: Reachability ⇒
      records.size == other.records.size && versions == versions &&
        cache.observerRowsMap == other.cache.observerRowsMap
    case _ ⇒ false
  }

  override def toString: String = {
    val rows = for {
      observer ← versions.keys.toSeq.sorted
      rowsOption = observerRows(observer)
      if rowsOption.isDefined // compilation err for subject <- rowsOption
      rows = rowsOption.get
      subject ← rows.keys.toSeq.sorted
    } yield {
      val record = rows(subject)
      val aggregated = status(subject)
      s"${observer.address} -> ${subject.address}: ${record.status} [$aggregated] (${record.version})"
    }

    rows.mkString(", ")
  }

}

