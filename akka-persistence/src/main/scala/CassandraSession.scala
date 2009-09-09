/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.state

import java.io.{Flushable, Closeable}

import util.Logging
import util.Helpers._
import serialization.Serializer
import Config.config

import org.apache.cassandra.db.ColumnFamily
import org.apache.cassandra.service._

import org.apache.commons.pool._
import org.apache.commons.pool.impl._

import org.apache.thrift.transport._
import org.apache.thrift.protocol._

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait CassandraSession extends Closeable with Flushable {
  import scala.collection.jcl.Conversions._
  import org.scala_tools.javautils.Imports._
  import java.util.{Map => JMap}

  protected val client: Cassandra.Client
  protected val keyspace: String

  val obtainedAt: Long
  val consistencyLevel: Int
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
  def /(key: String, columnParent: ColumnParent, start: Array[Byte], end: Array[Byte], ascending: Boolean, count: Int): List[Column] =
    /(key, columnParent, start, end, ascending, count, consistencyLevel)

  def /(key: String, columnParent: ColumnParent, start: Array[Byte], end: Array[Byte], ascending: Boolean, count: Int, consistencyLevel: Int): List[Column] =
    client.get_slice(keyspace, key, columnParent, start, end, ascending, count, consistencyLevel).toList

  def /(key: String, columnParent: ColumnParent, colNames: List[Array[Byte]]): List[Column] =
    /(key, columnParent, colNames, consistencyLevel)

  def /(key: String, columnParent: ColumnParent, colNames: List[Array[Byte]], consistencyLevel: Int): List[Column] =
    client.get_slice_by_names(keyspace, key, columnParent, colNames.asJava, consistencyLevel).toList

  def |(key: String, colPath: ColumnPath): Option[Column] =
    |(key, colPath, consistencyLevel)

  def |(key: String, colPath: ColumnPath, consistencyLevel: Int): Option[Column] =
    client.get_column(keyspace, key, colPath, consistencyLevel)

  def |#(key: String, columnParent: ColumnParent): Int =
    |#(key, columnParent, consistencyLevel)

  def |#(key: String, columnParent: ColumnParent, consistencyLevel: Int): Int =
    client.get_column_count(keyspace, key, columnParent, consistencyLevel)

  def ++|(key: String, colPath: ColumnPath, value: Array[Byte]): Unit =
    ++|(key, colPath, value, obtainedAt, consistencyLevel)

  def ++|(key: String, colPath: ColumnPath, value: Array[Byte], timestamp: Long): Unit =
    ++|(key, colPath, value, timestamp, consistencyLevel)

  def ++|(key: String, colPath: ColumnPath, value: Array[Byte], timestamp: Long, consistencyLevel: Int) =
    client.insert(keyspace, key, colPath, value, timestamp, consistencyLevel)

  def ++|(batch: BatchMutation): Unit =
    ++|(batch, consistencyLevel)

  def ++|(batch: BatchMutation, consistencyLevel: Int): Unit =
    client.batch_insert(keyspace, batch, consistencyLevel)

  def --(key: String, columnPathOrParent: ColumnPathOrParent, timestamp: Long): Unit =
    --(key, columnPathOrParent, timestamp, consistencyLevel)

  def --(key: String, columnPathOrParent: ColumnPathOrParent, timestamp: Long, consistencyLevel: Int): Unit =
    client.remove(keyspace, key, columnPathOrParent, timestamp, consistencyLevel)

  def /^(key: String, columnFamily: String, start: Array[Byte], end: Array[Byte], ascending: Boolean, count: Int): List[SuperColumn] =
    /^(key, columnFamily, start, end, ascending, count, consistencyLevel)

  def /^(key: String, columnFamily: String, start: Array[Byte], end: Array[Byte], ascending: Boolean, count: Int, consistencyLevel: Int): List[SuperColumn] =
    client.get_slice_super(keyspace, key, columnFamily, start, end, ascending, count, consistencyLevel).toList

  def /^(key: String, columnFamily: String, superColNames: List[Array[Byte]]): List[SuperColumn] =
    /^(key, columnFamily, superColNames, consistencyLevel)

  def /^(key: String, columnFamily: String, superColNames: List[Array[Byte]], consistencyLevel: Int): List[SuperColumn] =
    client.get_slice_super_by_names(keyspace, key, columnFamily, superColNames.asJava, consistencyLevel).toList

  def |^(key: String, superColumnPath: SuperColumnPath): Option[SuperColumn] =
    |^(key, superColumnPath, consistencyLevel)

  def |^(key: String, superColumnPath: SuperColumnPath, consistencyLevel: Int): Option[SuperColumn] =
    client.get_super_column(keyspace, key, superColumnPath, consistencyLevel)

  def ++|^(batch: BatchMutationSuper): Unit =
    ++|^(batch, consistencyLevel)

  def ++|^(batch: BatchMutationSuper, consistencyLevel: Int): Unit =
    client.batch_insert_super_column(keyspace, batch, consistencyLevel)

  def getRange(key: String, columnParent: ColumnParent, start: Array[Byte], end: Array[Byte], ascending: Boolean, count: Int): List[Column] =
    getRange(key, columnParent, start, end, ascending, count, consistencyLevel)

  def getRange(key: String, columnParent: ColumnParent, start: Array[Byte], end: Array[Byte], ascending: Boolean, count: Int, consistencyLevel: Int): List[Column] =
    client.get_slice(keyspace, key, columnParent, start, end, ascending, count, consistencyLevel).toList

  def getRange(key: String, columnParent: ColumnParent, colNames: List[Array[Byte]]): List[Column] =
    getRange(key, columnParent, colNames, consistencyLevel)

  def getRange(key: String, columnParent: ColumnParent, colNames: List[Array[Byte]], consistencyLevel: Int): List[Column] =
    client.get_slice_by_names(keyspace, key, columnParent, colNames.asJava, consistencyLevel).toList

  def getColumn(key: String, colPath: ColumnPath): Option[Column] =
    getColumn(key, colPath, consistencyLevel)

  def getColumn(key: String, colPath: ColumnPath, consistencyLevel: Int): Option[Column] =
    client.get_column(keyspace, key, colPath, consistencyLevel)

  def getColumnCount(key: String, columnParent: ColumnParent): Int =
    getColumnCount(key, columnParent, consistencyLevel)

  def getColumnCount(key: String, columnParent: ColumnParent, consistencyLevel: Int): Int =
    client.get_column_count(keyspace, key, columnParent, consistencyLevel)

  def insertColumn(key: String, colPath: ColumnPath, value: Array[Byte]): Unit =
    insertColumn(key, colPath, value, obtainedAt, consistencyLevel)

  def insertColumn(key: String, colPath: ColumnPath, value: Array[Byte], timestamp: Long): Unit =
    insertColumn(key, colPath, value, timestamp, consistencyLevel)

  def insertColumn(key: String, colPath: ColumnPath, value: Array[Byte], timestamp: Long, consistencyLevel: Int) =
    client.insert(keyspace, key, colPath, value, timestamp, consistencyLevel)

  def insertColumn(batch: BatchMutation): Unit =
    insertColumn(batch, consistencyLevel)

  def insertColumn(batch: BatchMutation, consistencyLevel: Int): Unit =
    client.batch_insert(keyspace, batch, consistencyLevel)

  def removeColumn(key: String, columnPathOrParent: ColumnPathOrParent, timestamp: Long): Unit =
    removeColumn(key, columnPathOrParent, timestamp, consistencyLevel)

  def removeColumn(key: String, columnPathOrParent: ColumnPathOrParent, timestamp: Long, consistencyLevel: Int): Unit =
    client.remove(keyspace, key, columnPathOrParent, timestamp, consistencyLevel)

  def getSuperRange(key: String, columnFamily: String, start: Array[Byte], end: Array[Byte], ascending: Boolean, count: Int): List[SuperColumn] =
    getSuperRange(key, columnFamily, start, end, ascending, count, consistencyLevel)

  def getSuperRange(key: String, columnFamily: String, start: Array[Byte], end: Array[Byte], ascending: Boolean, count: Int, consistencyLevel: Int): List[SuperColumn] =
    client.get_slice_super(keyspace, key, columnFamily, start, end, ascending, count, consistencyLevel).toList

  def getSuperRange(key: String, columnFamily: String, superColNames: List[Array[Byte]]): List[SuperColumn] =
    getSuperRange(key, columnFamily, superColNames, consistencyLevel)

  def getSuperRange(key: String, columnFamily: String, superColNames: List[Array[Byte]], consistencyLevel: Int): List[SuperColumn] =
    client.get_slice_super_by_names(keyspace, key, columnFamily, superColNames.asJava, consistencyLevel).toList

  def getSuperColumn(key: String, superColumnPath: SuperColumnPath): Option[SuperColumn] =
    getSuperColumn(key, superColumnPath, consistencyLevel)

  def getSuperColumn(key: String, superColumnPath: SuperColumnPath, consistencyLevel: Int): Option[SuperColumn] =
    client.get_super_column(keyspace, key, superColumnPath, consistencyLevel)

  def insertSuperColumn(batch: BatchMutationSuper): Unit =
    insertSuperColumn(batch, consistencyLevel)

  def insertSuperColumn(batch: BatchMutationSuper, consistencyLevel: Int): Unit =
    client.batch_insert_super_column(keyspace, batch, consistencyLevel)

  def keys(columnFamily: String, startsWith: String, stopsAt: String, maxResults: Option[Int]): List[String] =
    client.get_key_range(keyspace, columnFamily, startsWith, stopsAt, maxResults.getOrElse(-1)).toList
}

class CassandraSessionPool[T <: TTransport](
  space: String,
  transportPool: Pool[T],
  inputProtocol: Protocol,
  outputProtocol: Protocol,
  consistency: Int) extends Closeable with Logging {

  def this(space: String, transportPool: Pool[T], ioProtocol: Protocol, consistency: Int) =
    this (space, transportPool, ioProtocol, ioProtocol, consistency)

  def newSession: CassandraSession = newSession(consistency)

  def newSession(consistencyLevel: Int): CassandraSession = {
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

trait Pool[T] extends java.io.Closeable {
  def borrowObject: T
  def returnObject(t: T): Unit
  def invalidateObject(t: T): Unit
  def addObject: Unit
  def getNumIdle: Int
  def getNumActive: Int
  def clear: Unit
  def setFactory(factory: PoolItemFactory[T]): Unit
}

trait PoolFactory[T] {
  def createPool: Pool[T]
}

trait PoolItemFactory[T] {
  def makeObject: T
  def destroyObject(t: T): Unit
  def validateObject(t: T): Boolean
  def activateObject(t: T): Unit
  def passivateObject(t: T): Unit
}

trait PoolBridge[T, OP <: ObjectPool] extends Pool[T] {
  val impl: OP
  override def borrowObject: T = impl.borrowObject.asInstanceOf[T]
  override def returnObject(t: T) = impl.returnObject(t)
  override def invalidateObject(t: T) = impl.invalidateObject(t)
  override def addObject = impl.addObject
  override def getNumIdle: Int = impl.getNumIdle
  override def getNumActive: Int = impl.getNumActive
  override def clear: Unit = impl.clear
  override def close: Unit = impl.close
  override def setFactory(factory: PoolItemFactory[T]) = impl.setFactory(toPoolableObjectFactory(factory))

  def toPoolableObjectFactory[T](pif: PoolItemFactory[T]) = new PoolableObjectFactory {
    def makeObject: Object = pif.makeObject.asInstanceOf[Object]
    def destroyObject(o: Object): Unit = pif.destroyObject(o.asInstanceOf[T])
    def validateObject(o: Object): Boolean = pif.validateObject(o.asInstanceOf[T])
    def activateObject(o: Object): Unit = pif.activateObject(o.asInstanceOf[T])
    def passivateObject(o: Object): Unit = pif.passivateObject(o.asInstanceOf[T])
  }
}

object StackPool {
  def apply[T](factory: PoolItemFactory[T]) = new PoolBridge[T,StackObjectPool] {
    val impl = new StackObjectPool(toPoolableObjectFactory(factory))
  }

  def apply[T](factory: PoolItemFactory[T], maxIdle: Int) = new PoolBridge[T,StackObjectPool] {
    val impl = new StackObjectPool(toPoolableObjectFactory(factory),maxIdle)
  }

  def apply[T](factory: PoolItemFactory[T], maxIdle: Int, initIdleCapacity: Int) = new PoolBridge[T,StackObjectPool] {
    val impl = new StackObjectPool(toPoolableObjectFactory(factory),maxIdle,initIdleCapacity)
  }
}

object SoftRefPool {
  def apply[T](factory: PoolItemFactory[T]) = new PoolBridge[T,SoftReferenceObjectPool] {
    val impl = new SoftReferenceObjectPool(toPoolableObjectFactory(factory))
  }

  def apply[T](factory: PoolItemFactory[T], initSize: Int) = new PoolBridge[T,SoftReferenceObjectPool] {
    val impl = new SoftReferenceObjectPool(toPoolableObjectFactory(factory),initSize)
  }
}

trait TransportFactory[T <: TTransport] extends PoolItemFactory[T] {
  def createTransport: T
  def makeObject: T = createTransport
  def destroyObject(transport: T): Unit = transport.close
  def validateObject(transport: T) = transport.isOpen
  def activateObject(transport: T): Unit = if( !transport.isOpen ) transport.open else ()
  def passivateObject(transport: T): Unit = transport.flush
}

case class SocketProvider(val host: String, val port: Int) extends TransportFactory[TSocket] {
  def createTransport = {
    val t = new TSocket(host, port)
    t.open
    t
  }
}

