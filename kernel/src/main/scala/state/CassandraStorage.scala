/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.state

import java.io.{File, Flushable, Closeable}

import kernel.util.Logging
import serialization.{Serializer, Serializable, SerializationProtocol}

import org.apache.cassandra.db._
import org.apache.cassandra.config.DatabaseDescriptor
import org.apache.cassandra.service._

//import org.apache.thrift.server.TThreadPoolServer
import org.apache.thrift.TProcessorFactory
import org.apache.thrift.transport._
import org.apache.thrift._
import org.apache.thrift.transport._
import org.apache.thrift.protocol._

/**
 * NOTE: requires command line options:
 * <br/>
 *   <code>-Dcassandra -Dstorage-config=config/ -Dpidfile=akka.pid</code>
 * <p/>
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object CassandraStorage extends Logging {
  val TABLE_NAME = "akka"
  val MAP_COLUMN_FAMILY = "map"
  val VECTOR_COLUMN_FAMILY = "vector"
  val REF_COLUMN_FAMILY = "ref:item"

  val DEFAULT_CONSISTENCY_LEVEL = 10 //What should the default be?

  val IS_ASCENDING = true

  import kernel.Kernel.config

  val CASSANDRA_SERVER_HOSTNAME = config.getString("akka.storage.cassandra.hostname", "localhost")
  val CASSANDRA_SERVER_PORT = config.getInt("akka.storage.cassandra.port", 9160)
  val BLOCKING_CALL = if (config.getBool("akka.storage.cassandra.blocking", true)) 0
  else 1

  @volatile private[this] var isRunning = false
  private[this] val protocol: Protocol = {
    config.getString("akka.storage.cassandra.storage-format", "binary") match {
      case "binary" => Protocol.Binary
      case "json" => Protocol.JSON
      case "simple-json" => Protocol.SimpleJSON
      case unknown => throw new UnsupportedOperationException("Unknown storage serialization protocol [" + unknown + "]")
    }
  }


  private[this] var sessions: Option[CassandraSessionPool[_]] = None

  def start = synchronized {
    if (!isRunning) {
      try {
        sessions = Some(new CassandraSessionPool(StackPool(SocketProvider(CASSANDRA_SERVER_HOSTNAME, CASSANDRA_SERVER_PORT)), protocol, DEFAULT_CONSISTENCY_LEVEL))
        log.info("Cassandra persistent storage has started up successfully");
      } catch {
        case e =>
          log.error("Could not start up Cassandra persistent storage")
          throw e
      }
      isRunning
    }
  }

  def stop = synchronized {
    if (isRunning && sessions.isDefined) sessions.get.close
  }

  //implicit def strToBytes(s: String) = s.getBytes("UTF-8")

  /*
    def insertRefStorageFor(name: String, element: AnyRef) = sessions.withSession { session => {
      val user_id = "1"
      session ++| ("users", user_id, "base_attributes:name", "Lord Foo Bar", false)
      session ++| ("users", user_id, "base_attributes:age", "24", false)
      for( i <- session / ("users", user_id, "base_attributes", None, None).toList) println(i)
    }}
  */
  // ===============================================================
  // For Ref
  // ===============================================================

  def insertRefStorageFor(name: String, element: String) = if (sessions.isDefined) {
    sessions.get.withSession {
      _ ++| (
              TABLE_NAME,
              name,
              REF_COLUMN_FAMILY,
              element,
              System.currentTimeMillis,
              BLOCKING_CALL)
    }
  } else throw new IllegalStateException("CassandraStorage is not started")

  def getRefStorageFor(name: String): Option[String] = if (sessions.isDefined) {
    try {
      val column = sessions.get.withSession {_ | (TABLE_NAME, name, REF_COLUMN_FAMILY)}
      Some(column.value)
    } catch {
      case e =>
        e.printStackTrace
        None
    }
  } else throw new IllegalStateException("CassandraStorage is not started")

  // ===============================================================
  // For Vector
  // ===============================================================

  def insertVectorStorageEntryFor(name: String, element: String) = if (sessions.isDefined) {
    sessions.get.withSession {
      _ ++| (
              TABLE_NAME,
              name,
              VECTOR_COLUMN_FAMILY + ":" + getVectorStorageSizeFor(name),
              element,
              System.currentTimeMillis,
              BLOCKING_CALL)
    }
  } else throw new IllegalStateException("CassandraStorage is not started")

  def getVectorStorageEntryFor(name: String, index: Int): String = if (sessions.isDefined) {
    try {
      val column = sessions.get.withSession {_ | (TABLE_NAME, name, VECTOR_COLUMN_FAMILY + ":" + index)}
      column.value
    } catch {
      case e =>
        e.printStackTrace
        throw new NoSuchElementException(e.getMessage)
    }
  } else throw new IllegalStateException("CassandraStorage is not started")

  def getVectorStorageRangeFor(name: String, start: Int, count: Int): List[String] = if (sessions.isDefined) {
    sessions.get.withSession {_ / (TABLE_NAME, name, VECTOR_COLUMN_FAMILY, IS_ASCENDING, count)}.map(_.value)
  } else throw new IllegalStateException("CassandraStorage is not started")

  def getVectorStorageSizeFor(name: String): Int = if (sessions.isDefined) {
    sessions.get.withSession {_ |# (TABLE_NAME, name, VECTOR_COLUMN_FAMILY)}
  } else throw new IllegalStateException("CassandraStorage is not started")

  // ===============================================================
  // For Map
  // ===============================================================

  def insertMapStorageEntryFor(name: String, key: String, value: String) = if (sessions.isDefined) {
    sessions.get.withSession {
      _ ++| (
              TABLE_NAME,
              name,
              MAP_COLUMN_FAMILY + ":" + key,
              value,
              System.currentTimeMillis,
              BLOCKING_CALL)
    }
  } else throw new IllegalStateException("CassandraStorage is not started")

  def insertMapStorageEntriesFor(name: String, entries: List[Tuple2[String, String]]) = if (sessions.isDefined) {
    import java.util.{Map, HashMap, List, ArrayList}
    val columns: Map[String, List[column_t]] = new HashMap
    for (entry <- entries) {
      val cls: List[column_t] = new ArrayList
      cls.add(new column_t(entry._1, entry._2, System.currentTimeMillis))
      columns.put(MAP_COLUMN_FAMILY, cls)
    }
    sessions.get.withSession {
      _ ++| (
              new batch_mutation_t(
                TABLE_NAME,
                name,
                columns),
              BLOCKING_CALL)
    }
  } else throw new IllegalStateException("CassandraStorage is not started")

  def getMapStorageEntryFor(name: String, key: String): Option[String] = if (sessions.isDefined) {
    try {
      val column = sessions.get.withSession {_ | (TABLE_NAME, name, MAP_COLUMN_FAMILY + ":" + key)}
      Some(column.value)
    } catch {
      case e =>
        e.printStackTrace
        None
    }
  } else throw new IllegalStateException("CassandraStorage is not started")

  /*
  def getMapStorageFor(name: String): List[Tuple2[String, String]]  = if (sessions.isDefined) {
    val columns = server.get_columns_since(TABLE_NAME, name, MAP_COLUMN_FAMILY, -1)
      .toArray.toList.asInstanceOf[List[org.apache.cassandra.service.column_t]]
    for {
      column <- columns
      col = (column.columnName, column.value)
    } yield col
  } else throw new IllegalStateException("CassandraStorage is not started")
  */

  def getMapStorageSizeFor(name: String): Int = if (sessions.isDefined) {
    sessions.get.withSession {_ |# (TABLE_NAME, name, MAP_COLUMN_FAMILY)}
  } else throw new IllegalStateException("CassandraStorage is not started")

  def removeMapStorageFor(name: String) = if (sessions.isDefined) {
    sessions.get.withSession {_ -- (TABLE_NAME, name, MAP_COLUMN_FAMILY, System.currentTimeMillis, BLOCKING_CALL)}
  } else throw new IllegalStateException("CassandraStorage is not started")

  def getMapStorageRangeFor(name: String, start: Int, count: Int): List[Tuple2[String, String]] = if (sessions.isDefined) {
    sessions.get.withSession {_ / (TABLE_NAME, name, MAP_COLUMN_FAMILY, IS_ASCENDING, count)}.toArray.toList.asInstanceOf[List[Tuple2[String, String]]]
  } else throw new IllegalStateException("CassandraStorage is not started")
}

trait CassandraSession extends Closeable with Flushable {
  import scala.collection.jcl.Conversions._
  import org.scala_tools.javautils.Imports._

  private implicit def null2Option[T](t: T): Option[T] = if (t != null) Some(t) else None

  protected val client: Cassandra.Client

  val obtainedAt: Long
  val consistencyLevel: Int

  def /(keyspace: String, key: String, columnParent: ColumnParent, start: Array[Byte], end: Array[Byte], ascending: Boolean, count: Int): List[Column] =
    /(keyspace, key, columnParent, start, end, ascending, count, consistencyLevel)

  def /(keyspace: String, key: String, columnParent: ColumnParent, start: Array[Byte], end: Array[Byte], ascending: Boolean, count: Int, consistencyLevel: Int): List[Column] =
    client.get_slice(keyspace, key, columnParent, start, end, ascending, count, consistencyLevel).toList

  def /(keyspace: String, key: String, columnParent: ColumnParent, colNames: List[Array[Byte]]): List[Column] =
    /(keyspace, key, columnParent, colNames, consistencyLevel)

  def /(keyspace: String, key: String, columnParent: ColumnParent, colNames: List[Array[Byte]], consistencyLevel: Int): List[Column] =
    client.get_slice_by_names(keyspace, key, columnParent, colNames.asJava, consistencyLevel).toList

  def |(keyspace: String, key: String, colPath: ColumnPath): Option[Column] =
    |(keyspace, key, colPath, consistencyLevel)

  def |(keyspace: String, key: String, colPath: ColumnPath, consistencyLevel: Int): Option[Column] =
    client.get_column(keyspace, key, colPath, consistencyLevel)

  def |#(keyspace: String, key: String, columnParent: ColumnParent): Int =
    |#(keyspace, key, columnParent, consistencyLevel)

  def |#(keyspace: String, key: String, columnParent: ColumnParent, consistencyLevel: Int): Int =
    client.get_column_count(keyspace, key, columnParent, consistencyLevel)

  def ++|(keyspace: String, key: String, columnPath: ColumnPath, value: Array[Byte]): Unit =
    ++|(keyspace, key, columnPath, value, obtainedAt, consistencyLevel)

  def ++|(keyspace: String, key: String, columnPath: ColumnPath, value: Array[Byte], timestamp: Long): Unit =
    ++|(keyspace, key, columnPath, value, timestamp, consistencyLevel)

  def ++|(keyspace: String, key: String, columnPath: ColumnPath, value: Array[Byte], timestamp: Long, consistencyLevel: Int) =
    client.insert(keyspace, key, columnPath, value, timestamp, consistencyLevel)

  def ++|(keyspace: String, batch: BatchMutation): Unit =
    ++|(keyspace, batch, consistencyLevel)

  def ++|(keyspace: String, batch: BatchMutation, consistencyLevel: Int): Unit =
    client.batch_insert(keyspace, batch, consistencyLevel)

  def --(keyspace: String, key: String, columnPathOrParent: ColumnPathOrParent, timestamp: Long): Unit =
    --(keyspace, key, columnPathOrParent, timestamp, consistencyLevel)

  def --(keyspace: String, key: String, columnPathOrParent: ColumnPathOrParent, timestamp: Long, consistencyLevel: Int): Unit =
    client.remove(keyspace, key, columnPathOrParent, timestamp, consistencyLevel)

  def /^(keyspace: String, key: String, columnFamily: String, start: Array[Byte], end: Array[Byte], ascending: Boolean, count: Int): List[SuperColumn] =
    /^(keyspace, key, columnFamily, start, end, ascending, count, consistencyLevel)

  def /^(keyspace: String, key: String, columnFamily: String, start: Array[Byte], end: Array[Byte], ascending: Boolean, count: Int, consistencyLevel: Int): List[SuperColumn] =
    client.get_slice_super(keyspace, key, columnFamily, start, end, ascending, count, consistencyLevel).toList

  def /^(keyspace: String, key: String, columnFamily: String, superColNames: List[Array[Byte]]): List[SuperColumn] =
    /^(keyspace, key, columnFamily, superColNames, consistencyLevel)

  def /^(keyspace: String, key: String, columnFamily: String, superColNames: List[Array[Byte]], consistencyLevel: Int): List[SuperColumn] =
    client.get_slice_super_by_names(keyspace, key, columnFamily, superColNames.asJava, consistencyLevel).toList

  def |^(keyspace: String, key: String, superColumnPath: SuperColumnPath): Option[SuperColumn] =
    |^(keyspace, key, superColumnPath, consistencyLevel)

  def |^(keyspace: String, key: String, superColumnPath: SuperColumnPath, consistencyLevel: Int): Option[SuperColumn] =
    client.get_super_column(keyspace, key, superColumnPath, consistencyLevel)

  def ++|^(keyspace: String, batch: BatchMutationSuper): Unit =
    ++|^(keyspace, batch, consistencyLevel)

  def ++|^(keyspace: String, batch: BatchMutationSuper, consistencyLevel: Int): Unit =
    client.batch_insert_super_column(keyspace, batch, consistencyLevel)

  def keys(keyspace: String, columnFamily: String, startsWith: String, stopsAt: String, maxResults: Option[Int]): List[String] =
    client.get_key_range(keyspace, columnFamily, startsWith, stopsAt, maxResults.getOrElse(-1)).toList

  def property(name: String): String = client.get_string_property(name)

  def properties(name: String): List[String] = client.get_string_list_property(name).toList

  def describeTable(keyspace: String) = client.describe_keyspace(keyspace)

  def ?(query: String) = client.execute_query(query)
}

class CassandraSessionPool[T <: TTransport](transportPool: Pool[T], inputProtocol: Protocol, outputProtocol: Protocol, defConsistencyLvl: Int) extends Closeable {
  def this(transportPool: Pool[T], ioProtocol: Protocol, defConsistencyLvl: Int) = this (transportPool, ioProtocol, ioProtocol, defConsistencyLvl)

  def newSession: CassandraSession = newSession(defConsistencyLvl)

  def newSession(consistencyLvl: Int): CassandraSession = {
    val t = transportPool.borrowObject
    val c = new Cassandra.Client(inputProtocol(t), outputProtocol(t))
    new CassandraSession {
      val client = c
      val obtainedAt = System.currentTimeMillis
      val consistencyLevel = consistencyLvl
      def flush = t.flush
      def close = transportPool.returnObject(t)
    }
  }

  def withSession[R](body: CassandraSession => R) = {
    val session = newSession(defConsistencyLvl)
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

/**
 * NOTE: requires command line options:
 * <br/>
 *   <code>-Dcassandra -Dstorage-config=config/ -Dpidfile=akka.pid</code>
 * <p/>
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 *
object EmbeddedCassandraStorage extends Logging  {
val TABLE_NAME = "akka"
val MAP_COLUMN_FAMILY = "map"
val VECTOR_COLUMN_FAMILY = "vector"
val REF_COLUMN_FAMILY = "ref:item"

val IS_ASCENDING = true

val RUN_THRIFT_SERVICE = kernel.Kernel.config.getBool("akka.storage.cassandra.thrift-server.service", false)
val BLOCKING_CALL =  {
if (kernel.Kernel.config.getBool("akka.storage.cassandra.blocking", true)) 0
else 1 }

@volatile private[this] var isRunning = false
private[this] val serializer: Serializer =  {
kernel.Kernel.config.getString("akka.storage.cassandra.storage-format", "java") match  {
case "scala-json" => Serializer.ScalaJSON
case "java-json" =>  Serializer.JavaJSON
case "protobuf" =>   Serializer.Protobuf
case "java" =>       Serializer.Java
case "sbinary" =>    throw new UnsupportedOperationException("SBinary serialization protocol is not yet supported for storage")
case "avro" =>       throw new UnsupportedOperationException("Avro serialization protocol is not yet supported for storage")
case unknown =>      throw new UnsupportedOperationException("Unknown storage serialization protocol [" + unknown + "]")
}
}

// TODO: is this server thread-safe or needed to be wrapped up in an actor?
private[this] val server = classOf[CassandraServer].newInstance.asInstanceOf[CassandraServer]

private[this] var thriftServer: CassandraThriftServer = _

def start = synchronized  {
if (!isRunning)  {
try  {
server.start
log.info("Cassandra persistent storage has started up successfully");
} catch  {
case e =>
log.error("Could not start up Cassandra persistent storage")
throw e
}
if (RUN_THRIFT_SERVICE)  {
thriftServer = new CassandraThriftServer(server)
thriftServer.start
}
isRunning
}
}

def stop = if (isRunning)  {
//server.storageService.shutdown
if (RUN_THRIFT_SERVICE) thriftServer.stop
}

// ===============================================================
// For Ref
// ===============================================================

def insertRefStorageFor(name: String, element: AnyRef) =  {
server.insert(
TABLE_NAME,
name,
REF_COLUMN_FAMILY,
element,
System.currentTimeMillis,
BLOCKING_CALL)
}

def getRefStorageFor(name: String): Option[AnyRef] =  {
try  {
val column = server.get_column(TABLE_NAME, name, REF_COLUMN_FAMILY)
Some(serializer.in(column.value, None))
} catch  {
case e =>
e.printStackTrace
None }
}

// ===============================================================
// For Vector
// ===============================================================

def insertVectorStorageEntryFor(name: String, element: AnyRef) =  {
server.insert(
TABLE_NAME,
name,
VECTOR_COLUMN_FAMILY + ":" + getVectorStorageSizeFor(name),
element,
System.currentTimeMillis,
BLOCKING_CALL)
}

def getVectorStorageEntryFor(name: String, index: Int): AnyRef =  {
try  {
val column = server.get_column(TABLE_NAME, name, VECTOR_COLUMN_FAMILY + ":" + index)
serializer.in(column.value, None)
} catch  {
case e =>
e.printStackTrace
throw new Predef.NoSuchElementException(e.getMessage)
}
}

def getVectorStorageRangeFor(name: String, start: Int, count: Int): List[AnyRef]  =
server.get_slice(TABLE_NAME, name, VECTOR_COLUMN_FAMILY, IS_ASCENDING, count)
.toArray.toList.asInstanceOf[List[Tuple2[String, AnyRef]]].map(tuple => tuple._2)

def getVectorStorageSizeFor(name: String): Int =
server.get_column_count(TABLE_NAME, name, VECTOR_COLUMN_FAMILY)

// ===============================================================
// For Map
// ===============================================================

def insertMapStorageEntryFor(name: String, key: String, value: AnyRef) =  {
server.insert(
TABLE_NAME, name,
MAP_COLUMN_FAMILY + ":" + key,
serializer.out(value),
System.currentTimeMillis,
BLOCKING_CALL)
}

def insertMapStorageEntriesFor(name: String, entries: List[Tuple2[String, AnyRef]]) =  {
import java.util.{ Map, HashMap, List, ArrayList }
val columns: Map[String, List[column_t]] = new HashMap
for (entry <- entries)  {
val cls: List[column_t] = new ArrayList
cls.add(new column_t(entry._1, serializer.out(entry._2), System.currentTimeMillis))
columns.put(MAP_COLUMN_FAMILY, cls)
}
server.batch_insert(new batch_mutation_t(
TABLE_NAME, name,
columns),
BLOCKING_CALL)
}

def getMapStorageEntryFor(name: String, key: AnyRef): Option[AnyRef] =  {
try  {
val column = server.get_column(TABLE_NAME, name, MAP_COLUMN_FAMILY + ":" + key)
Some(serializer.in(column.value, None))
} catch  {
case e =>
e.printStackTrace
None
}
}

def getMapStorageFor(name: String): List[Tuple2[String, AnyRef]]  =  {
val columns = server.get_columns_since(TABLE_NAME, name, MAP_COLUMN_FAMILY, -1)
.toArray.toList.asInstanceOf[List[org.apache.cassandra.service.column_t]]
for  {
column <- columns
col = (column.columnName, serializer.in(column.value, None))
} yield col
}

def getMapStorageSizeFor(name: String): Int =
server.get_column_count(TABLE_NAME, name, MAP_COLUMN_FAMILY)

def removeMapStorageFor(name: String) =
server.remove(TABLE_NAME, name, MAP_COLUMN_FAMILY, System.currentTimeMillis, BLOCKING_CALL)

def getMapStorageRangeFor(name: String, start: Int, count: Int): List[Tuple2[String, AnyRef]] =  {
server.get_slice(TABLE_NAME, name, MAP_COLUMN_FAMILY, IS_ASCENDING, count)
.toArray.toList.asInstanceOf[List[Tuple2[String, AnyRef]]]
}
}


class CassandraThriftServer(server: CassandraServer) extends Logging  {
case object Start
case object Stop

private[this] val serverEngine: TThreadPoolServer = try  {
val pidFile = kernel.Kernel.config.getString("akka.storage.cassandra.thrift-server.pidfile", "akka.pid")
if (pidFile != null) new File(pidFile).deleteOnExit();
val listenPort = DatabaseDescriptor.getThriftPort

val processor = new Cassandra.Processor(server)
val tServerSocket = new TServerSocket(listenPort)
val tProtocolFactory = new TBinaryProtocol.Factory

val options = new TThreadPoolServer.Options
options.minWorkerThreads = 64
new TThreadPoolServer(new TProcessorFactory(processor),
tServerSocket,
new TTransportFactory,
new TTransportFactory,
tProtocolFactory,
tProtocolFactory,
options)
} catch  {
case e =>
log.error("Could not start up Cassandra thrift service")
throw e
}

import scala.actors.Actor._
private[this] val serverDaemon = actor  {
receive  {
case Start =>
serverEngine.serve
log.info("Cassandra thrift service has starting up successfully")
case Stop =>
log.info("Cassandra thrift service is shutting down...")
serverEngine.stop
}
}

def start = serverDaemon ! Start
def stop = serverDaemon ! Stop
}
 */
