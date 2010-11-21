/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.persistence.cassandra

import akka.stm._
import akka.persistence.common._
import akka.util.Logging
import akka.util.Helpers._
import akka.config.Config.config

import org.apache.cassandra.thrift._
import java.lang.String
import collection.JavaConversions
import collection.immutable.{TreeMap, Iterable}
import java.util.{Map => JMap, HashMap => JHMap, List => JList, ArrayList => JAList}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */

private[akka] object CassandraStorageBackend extends CommonStorageBackend {

  import CommonStorageBackend._

  type ElementType = Array[Byte]

  val KEYSPACE = "akka"
  val MAP_COLUMN_PARENT = new ColumnParent("map")
  val VECTOR_COLUMN_PARENT = new ColumnParent("vector")
  val REF_COLUMN_PARENT = new ColumnParent("ref")
  val QUEUE_COLUMN_PARENT = new ColumnParent("queue")
  val REF_KEY = "item".getBytes("UTF-8")
  val EMPTY_BYTE_ARRAY = new Array[Byte](0)

  val CASSANDRA_SERVER_HOSTNAME = config.getString("akka.storage.cassandra.hostname", "127.0.0.1")
  val CASSANDRA_SERVER_PORT = config.getInt("akka.storage.cassandra.port", 9160)
  val CONSISTENCY_LEVEL = {
    config.getString("akka.storage.cassandra.consistency-level", "QUORUM") match {
      case "ZERO" => ConsistencyLevel.ZERO
      case "ONE" => ConsistencyLevel.ONE
      case "QUORUM" => ConsistencyLevel.QUORUM
      case "DCQUORUM" => ConsistencyLevel.DCQUORUM
      case "DCQUORUMSYNC" => ConsistencyLevel.DCQUORUMSYNC
      case "ALL" => ConsistencyLevel.ALL
      case "ANY" => ConsistencyLevel.ANY
      case unknown => throw new IllegalArgumentException(
        "Cassandra consistency level [" + unknown + "] is not supported." +
          "\n\tExpected one of [ZERO, ONE, QUORUM, DCQUORUM, DCQUORUMSYNC, ALL, ANY] in the akka.conf configuration file.")
    }
  }
  val IS_ASCENDING = true

  @volatile private[this] var isRunning = false
  private[this] val protocol: Protocol = Protocol.Binary

  private[this] val sessions = new CassandraSessionPool(
    KEYSPACE,
    StackPool(SocketProvider(CASSANDRA_SERVER_HOSTNAME, CASSANDRA_SERVER_PORT)),
    protocol,
    CONSISTENCY_LEVEL)


  class CassandraAccess(parent: ColumnParent) extends CommonStorageBackendAccess {

    def path(key: Array[Byte]): ColumnPath = {
      new ColumnPath(parent.getColumn_family).setColumn(key)
    }

    def delete(owner: String, key: Array[Byte]) = {
      sessions.withSession{
        session => {
          session -- (owner, path(key), System.currentTimeMillis, CONSISTENCY_LEVEL)
        }
      }
    }

    override def getAll(owner: String, keys: Iterable[Array[Byte]]): Map[Array[Byte], Array[Byte]] = {
      sessions.withSession{
        session => {
          var predicate = new SlicePredicate().setColumn_names(JavaConversions.asList(keys.toList))
          val cols = session / (owner, parent, predicate, CONSISTENCY_LEVEL)
          var map = new TreeMap[Array[Byte], Array[Byte]]()(ordering)
          cols.foreach{
            cosc => map += cosc.getColumn.getName -> cosc.getColumn.getValue
          }
          map
        }
      }
    }


    def get(owner: String, key: Array[Byte], default: Array[Byte]) = {
      sessions.withSession{
        session => {
          try
          {
            session | (owner, path(key), CONSISTENCY_LEVEL) match {
              case Some(cosc) => cosc.getColumn.getValue
              case None => default
            }
          } catch {
            case e: NotFoundException => default
          }
        }
      }
    }

    def put(owner: String, key: Array[Byte], value: Array[Byte]) = {
      sessions.withSession{
        session => {
          session ++| (owner, path(key), value, System.currentTimeMillis, CONSISTENCY_LEVEL)
        }
      }
    }


    def drop() = {
      sessions.withSession{
        session => {
          val slices = session.client.get_range_slices(session.keyspace, parent,
            new SlicePredicate().setSlice_range(new SliceRange().setStart(Array.empty[Byte]).setFinish(Array.empty[Byte])),
            new KeyRange().setStart_key("").setEnd_key(""), CONSISTENCY_LEVEL)

          val mutations = new JHMap[String, JMap[String, JList[Mutation]]]
          JavaConversions.asIterable(slices).foreach{
            keySlice: KeySlice => {
              val key = keySlice.getKey
              val keyMutations = JavaConversions.asMap(mutations).getOrElse(key, {
                val km = new JHMap[String, JList[Mutation]]
                mutations.put(key, km)
                km
              })
              val amutation = new JAList[Mutation]
              val cols = new JAList[Array[Byte]]
              keyMutations.put(parent.getColumn_family, amutation)
              JavaConversions.asIterable(keySlice.getColumns) foreach {
                cosc: ColumnOrSuperColumn => {
                  cols.add(cosc.getColumn.getName)
                }
              }
              amutation.add(new Mutation().setDeletion(new Deletion(System.currentTimeMillis).setPredicate(new SlicePredicate().setColumn_names(cols))))

            }
          }
          session.client.batch_mutate(session.keyspace, mutations, CONSISTENCY_LEVEL)
        }
      }
    }

  }

  def queueAccess = new CassandraAccess(QUEUE_COLUMN_PARENT)

  def mapAccess = new CassandraAccess(MAP_COLUMN_PARENT)

  def vectorAccess = new CassandraAccess(VECTOR_COLUMN_PARENT)

  def refAccess = new CassandraAccess(REF_COLUMN_PARENT)
}
