/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.persistence.cassandra

import java.io.{Flushable, Closeable}

import akka.persistence.common._
import akka.util.Logging
import akka.util.Helpers._
import akka.serialization.Serializer
import akka.config.Config.config

import scala.collection.mutable.Map

import org.apache.cassandra.db.ColumnFamily
import org.apache.cassandra.thrift._

import org.apache.thrift.transport._
import org.apache.thrift.protocol._

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait CassandraSession extends Closeable with Flushable {
  import scala.collection.JavaConversions._
  import java.util.{Map => JMap, List => JList}

  val client: Cassandra.Client
  val keyspace: String

  val obtainedAt: Long
  val consistencyLevel: ConsistencyLevel
  val schema: JMap[String, JMap[String, String]]

  /**
   * Count is always the max number of results to return.

    So it means, starting with `start`, or the first one if start is
    empty, go until you hit `finish` or `count`, whichever comes first.
    Empty is not a legal column name so if finish is empty it is ignored
    and only count is used.

    We don't offer a numeric offset since that can't be supported
    efficiently with a log-structured merge disk format.
   */

  // ====================================
  // ====== Scala-style API names
  // ====================================

  def /(key: String, columnParent: ColumnParent, start: Array[Byte], end: Array[Byte], ascending: Boolean, count: Int): List[ColumnOrSuperColumn] =
    /(key, columnParent, start, end, ascending, count, consistencyLevel)

  def /(key: String, columnParent: ColumnParent, start: Array[Byte], end: Array[Byte], ascending: Boolean, count: Int, consistencyLevel: ConsistencyLevel): List[ColumnOrSuperColumn] = {
    val slicePredicate = new SlicePredicate
    slicePredicate.setSlice_range(new SliceRange(start, end, ascending, count))
    client.get_slice(keyspace, key, columnParent, slicePredicate, consistencyLevel).toList
  }

  def /(key: String, columnParent: ColumnParent, slicePredicate: SlicePredicate): List[ColumnOrSuperColumn] =
    client.get_slice(keyspace, key, columnParent, slicePredicate, consistencyLevel).toList

  def /(key: String, columnParent: ColumnParent, slicePredicate: SlicePredicate, consistencyLevel: ConsistencyLevel): List[ColumnOrSuperColumn] =
    client.get_slice(keyspace, key, columnParent, slicePredicate, consistencyLevel).toList

  def |(key: String, colPath: ColumnPath): Option[ColumnOrSuperColumn] =
    |(key, colPath, consistencyLevel)

  def |(key: String, colPath: ColumnPath, consistencyLevel: ConsistencyLevel): Option[ColumnOrSuperColumn] =
    client.get(keyspace, key, colPath, consistencyLevel)

  def |#(key: String, columnParent: ColumnParent): Int =
    |#(key, columnParent, consistencyLevel)

  def |#(key: String, columnParent: ColumnParent, consistencyLevel: ConsistencyLevel): Int =
    client.get_count(keyspace, key, columnParent, consistencyLevel)

  def ++|(key: String, colPath: ColumnPath, value: Array[Byte]): Unit =
    ++|(key, colPath, value, obtainedAt, consistencyLevel)

  def ++|(key: String, colPath: ColumnPath, value: Array[Byte], consistencyLevel: ConsistencyLevel): Unit =
    ++|(key, colPath, value, obtainedAt, consistencyLevel)

  def ++|(key: String, colPath: ColumnPath, value: Array[Byte], timestamp: Long): Unit =
    ++|(key, colPath, value, timestamp, consistencyLevel)

  def ++|(key: String, colPath: ColumnPath, value: Array[Byte], timestamp: Long, consistencyLevel: ConsistencyLevel) =
    client.insert(keyspace, key, colPath, value, timestamp, consistencyLevel)

  def ++|(key: String, batch: Map[String, List[ColumnOrSuperColumn]]): Unit =
    ++|(key, batch, consistencyLevel)

  def ++|(key: String, batch: Map[String, List[ColumnOrSuperColumn]], consistencyLevel: ConsistencyLevel): Unit = {
    val jmap = new java.util.HashMap[String, JList[ColumnOrSuperColumn]]
    for (entry <- batch; (key, value) = entry) jmap.put(key, new java.util.ArrayList(value))
    client.batch_insert(keyspace, key, jmap, consistencyLevel)
  }

  def --(key: String, columnPath: ColumnPath, timestamp: Long): Unit =
    --(key, columnPath, timestamp, consistencyLevel)

  def --(key: String, columnPath: ColumnPath, timestamp: Long, consistencyLevel: ConsistencyLevel): Unit =
    client.remove(keyspace, key, columnPath, timestamp, consistencyLevel)

  // ====================================
  // ====== Java-style API names
  // ====================================

  def getSlice(key: String, columnParent: ColumnParent, start: Array[Byte], end: Array[Byte], ascending: Boolean, count: Int) = / (key, columnParent, start, end, ascending, count, consistencyLevel)

  def getSlice(key: String, columnParent: ColumnParent, start: Array[Byte], end: Array[Byte], ascending: Boolean, count: Int, consistencyLevel: ConsistencyLevel) = / (key, columnParent, start, end, ascending, count, consistencyLevel)

  def getSlice(key: String, columnParent: ColumnParent, slicePredicate: SlicePredicate) = / (key, columnParent, slicePredicate)

  def getSlice(key: String, columnParent: ColumnParent, slicePredicate: SlicePredicate, consistencyLevel: ConsistencyLevel) = / (key, columnParent, slicePredicate, consistencyLevel)


  def get(key: String, colPath: ColumnPath) = |(key, colPath)

  def get(key: String, colPath: ColumnPath, consistencyLevel: ConsistencyLevel) = |(key, colPath, consistencyLevel)

  def getCount(key: String, columnParent: ColumnParent)= |#(key, columnParent)

  def getCount(key: String, columnParent: ColumnParent, consistencyLevel: ConsistencyLevel) = |#(key, columnParent, consistencyLevel)


  def insert(key: String, colPath: ColumnPath, value: Array[Byte]): Unit = ++|(key, colPath, value)

  def insert(key: String, colPath: ColumnPath, value: Array[Byte], consistencyLevel: ConsistencyLevel): Unit = ++|(key, colPath, value, consistencyLevel)

  def insert(key: String, colPath: ColumnPath, value: Array[Byte], timestamp: Long): Unit = ++|(key, colPath, value, timestamp)

  def insert(key: String, colPath: ColumnPath, value: Array[Byte], timestamp: Long, consistencyLevel: ConsistencyLevel) = ++|(key, colPath, value, timestamp, consistencyLevel)

  def insert(key: String, batch: Map[String, List[ColumnOrSuperColumn]]): Unit = ++|(key, batch)

  def insert(key: String, batch: Map[String, List[ColumnOrSuperColumn]], consistencyLevel: ConsistencyLevel): Unit = ++|(key, batch, consistencyLevel)

  def remove(key: String, columnPath: ColumnPath, timestamp: Long): Unit = --(key, columnPath, timestamp)

  def remove(key: String, columnPath: ColumnPath, timestamp: Long, consistencyLevel: ConsistencyLevel): Unit = --(key, columnPath, timestamp, consistencyLevel)

}

class CassandraSessionPool[T <: TTransport](
  space: String,
  transportPool: Pool[T],
  inputProtocol: Protocol,
  outputProtocol: Protocol,
  consistency: ConsistencyLevel) extends Closeable with Logging {

  def this(space: String, transportPool: Pool[T], ioProtocol: Protocol, consistency: ConsistencyLevel) =
    this (space, transportPool, ioProtocol, ioProtocol, consistency)

  def newSession: CassandraSession = newSession(consistency)

  def newSession(consistencyLevel: ConsistencyLevel): CassandraSession = {
    val socket = transportPool.borrowObject
    val cassandraClient = new Cassandra.Client(inputProtocol(socket), outputProtocol(socket))
    val cassandraSchema = cassandraClient.describe_keyspace(space)
    new CassandraSession {
      val keyspace = space
      val client = cassandraClient
      val obtainedAt = System.currentTimeMillis
      val consistencyLevel = consistency
      val schema = cassandraSchema
      log.debug("Creating %s", toString)

      def flush = socket.flush
      def close = transportPool.returnObject(socket)
      override def toString = "[CassandraSession]\n\tkeyspace = " + keyspace + "\n\tschema = " + schema
    }
  }

  def withSession[T](body: CassandraSession => T) = {
    val session = newSession(consistency)
    try {
      val result = body(session)
      session.flush
      result
    } finally {
      session.close
    }
  }

  def close = transportPool.close
}

sealed abstract class Protocol(val factory: TProtocolFactory) {
  def apply(transport: TTransport) = factory.getProtocol(transport)
}

object Protocol {
  object Binary extends Protocol(new TBinaryProtocol.Factory)
  object SimpleJSON extends Protocol(new TSimpleJSONProtocol.Factory)
  object JSON extends Protocol(new TJSONProtocol.Factory)
}
