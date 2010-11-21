/**
 *  Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.persistence.riak

import akka.persistence.common._
import akka.config.Config.config

import java.lang.String
import collection.JavaConversions
import collection.Map
import java.util.{Map => JMap}
import akka.persistence.common.PersistentMapBinary.COrdering._
import collection.immutable._
import com.google.protobuf.ByteString
import com.trifork.riak.{RequestMeta, RiakObject, RiakClient}


private[akka] object RiakStorageBackend extends CommonStorageBackend {
  val refBucket = config.getString("akka.storage.riak.bucket.ref", "Refs")
  val mapBucket = config.getString("akka.storage.riak.bucket.map", "Maps")
  val vectorBucket = config.getString("akka.storage.riak.bucket.vector", "Vectors")
  val queueBucket = config.getString("akka.storage.riak.bucket.queue", "Queues")
  val clientHost = config.getString("akka.storage.riak.client.host", "localhost")
  val clientPort = config.getInt("akka.storage.riak.client.port", 8087)
  val riakClient: RiakClient = new RiakClient(clientHost, clientPort);

  import CommonStorageBackendAccess._
  import KVStorageBackend._
  import RiakAccess._


  val refs = new RiakAccess(refBucket)
  val maps = new RiakAccess(mapBucket)
  val vectors = new RiakAccess(vectorBucket)
  val queues = new RiakAccess(queueBucket)

  def refAccess = refs

  def mapAccess = maps

  def vectorAccess = vectors

  def queueAccess = queues

  object RiakAccess {
    implicit def byteArrayToByteString(ary: Array[Byte]): ByteString = {
      ByteString.copyFrom(ary)
    }

    implicit def byteStringToByteArray(bs: ByteString): Array[Byte] = {
      bs.toByteArray
    }

    implicit def stringToByteString(bucket: String): ByteString = {
      ByteString.copyFromUtf8(bucket)
    }

  }


  class RiakAccess(val bucket: String) extends KVStorageBackendAccess {
    //http://www.mail-archive.com/riak-users@lists.basho.com/msg01013.html
    val quorum: Int = 0xfffffffd
    val one: Int = 0xfffffffe
    val all: Int = 0xfffffffc
    val default: Int = 0xfffffffb

    def put(key: Array[Byte], value: Array[Byte]) = {
      val objs: Array[RiakObject] = riakClient.fetch(bucket, key, quorum)
      objs.size match {
        case 0 => riakClient.store(new RiakObject(bucket, key, value), new RequestMeta().w(quorum).dw(quorum))
        case _ => riakClient.store(new RiakObject(objs(0).getVclock, bucket, key, value), new RequestMeta().w(quorum).dw(quorum))
      }
    }

    def get(key: Array[Byte]): Array[Byte] = {
      val objs = riakClient.fetch(bucket, key, quorum)
      objs.size match {
        case 0 => null;
        case _ => objs(0).getValue.isEmpty match {
          case true => null
          case false => objs(0).getValue
        }
      }
    }

    def get(key: Array[Byte], default: Array[Byte]): Array[Byte] = {
      Option(get(key)) match {
        case Some(value) => value
        case None => default
      }
    }

    def getAll(keys: Iterable[Array[Byte]]): Map[Array[Byte], Array[Byte]] = {
      var result = new HashMap[Array[Byte], Array[Byte]]
      keys.foreach{
        key =>
          val value = get(key)
          Option(value) match {
            case Some(value) => result += key -> value
            case None => ()
          }
      }
      result
    }

    def delete(key: Array[Byte]) = {
      riakClient.delete(bucket, key, quorum)
    }

    def drop() {
      val keys = riakClient.listKeys(bucket)
      JavaConversions.asIterable(keys) foreach {
        delete(_)
      }
      keys.close
    }
  }


}
