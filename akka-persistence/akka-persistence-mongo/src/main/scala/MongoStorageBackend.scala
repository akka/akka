/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.persistence.mongo

import se.scalablesolutions.akka.stm._
import se.scalablesolutions.akka.persistence.common._
import se.scalablesolutions.akka.util.Logging
import se.scalablesolutions.akka.config.Config.config

import java.util.NoSuchElementException
import com.novus.casbah.mongodb.Imports._

/**
 * A module for supporting MongoDB based persistence.
 * <p/>
 * The module offers functionality for:
 * <li>Persistent Maps</li>
 * <li>Persistent Vectors</li>
 * <li>Persistent Refs</li>
 * <p/>
 * @author <a href="http://debasishg.blogspot.com">Debasish Ghosh</a>
 */
private[akka] object MongoStorageBackend extends
  MapStorageBackend[Array[Byte], Array[Byte]] with
  VectorStorageBackend[Array[Byte]] with
  RefStorageBackend[Array[Byte]] with
  Logging {

  val KEY = "__key"
  val REF = "__ref"
  val COLLECTION = "akka_coll"

  val HOSTNAME = config.getString("akka.storage.mongodb.hostname", "127.0.0.1")
  val DBNAME = config.getString("akka.storage.mongodb.dbname", "testdb")
  val PORT = config.getInt("akka.storage.mongodb.port", 27017)

  val db: MongoDB = MongoConnection(HOSTNAME, PORT)(DBNAME)
  val coll: MongoCollection = db(COLLECTION)

  def drop() { db.dropDatabase() }

  def insertMapStorageEntryFor(name: String, key: Array[Byte], value: Array[Byte]) {
    insertMapStorageEntriesFor(name, List((key, value)))
  }

  def insertMapStorageEntriesFor(name: String, entries: List[(Array[Byte], Array[Byte])]) {
    val q: DBObject = MongoDBObject(KEY -> name)
    coll.findOne(q) match {
      case Some(dbo) => 
        entries.foreach { case (k, v) => dbo += new String(k) -> v }
        coll.update(q, dbo, true, false)
      case None => 
        val builder = MongoDBObject.newBuilder
        builder += KEY -> name
        entries.foreach { case (k, v) => builder += new String(k) -> v }
        coll += builder.result.asDBObject
    }
  }

  def removeMapStorageFor(name: String): Unit = {
    val q: DBObject = MongoDBObject(KEY -> name)
    coll.remove(q)
  }

  private def queryFor[T](name: String)(body: (MongoDBObject, MongoDBObject) => T): T = {
    val q: DBObject = MongoDBObject(KEY -> name)
    val dbo = coll.findOne(q).getOrElse { throw new NoSuchElementException(name + " not present") } 
    body(q, dbo)
  }

  def removeMapStorageFor(name: String, key: Array[Byte]): Unit = queryFor(name) { (q, dbo) =>
    dbo -= new String(key)
    coll.update(q, dbo, true, false)
  }

  def getMapStorageEntryFor(name: String, key: Array[Byte]): Option[Array[Byte]] = queryFor(name) { (q, dbo) =>
    dbo.get(new String(key)).asInstanceOf[Option[Array[Byte]]]
  }

  def getMapStorageSizeFor(name: String): Int = queryFor(name) { (q, dbo) =>
    dbo.size - 2 // need to exclude object id and our KEY
  }

  def getMapStorageFor(name: String): List[(Array[Byte], Array[Byte])]  = queryFor(name) { (q, dbo) =>
    for {
      (k, v) <- dbo.toList
      if k != "_id" && k != KEY
    } yield (k.getBytes, v.asInstanceOf[Array[Byte]])
  }

  def getMapStorageRangeFor(name: String, start: Option[Array[Byte]],
                            finish: Option[Array[Byte]],
                            count: Int): List[(Array[Byte], Array[Byte])] = queryFor(name) { (q, dbo) =>
    // get all keys except the special ones
    val keys = 
      dbo.keySet
         .toList
         .filter(k => k != "_id" && k != KEY)
         .sortWith(_ < _)

    // if the supplied start is not defined, get the head of keys
    val s = start.map(new String(_)).getOrElse(keys.head)

    // if the supplied finish is not defined, get the last element of keys
    val f = finish.map(new String(_)).getOrElse(keys.last)

    // slice from keys: both ends inclusive
    val ks = keys.slice(keys.indexOf(s), scala.math.min(count, keys.indexOf(f) + 1))
    ks.map(k => (k.getBytes, dbo.get(k).get.asInstanceOf[Array[Byte]]))
  }

  def insertVectorStorageEntryFor(name: String, element: Array[Byte]) = {
    insertVectorStorageEntriesFor(name, List(element))
  }

  def insertVectorStorageEntriesFor(name: String, elements: List[Array[Byte]]) = {
    // lookup with name
    val q: DBObject = MongoDBObject(KEY -> name)

    coll.findOne(q) match {
      // exists : need to update
      case Some(dbo) => 
        dbo -= KEY
        dbo -= "_id"
        val listBuilder = MongoDBList.newBuilder

        // expensive!
        listBuilder ++= (elements ++ dbo.toSeq.sortWith((e1, e2) => (e1._1.toInt < e2._1.toInt)).map(_._2))

        val builder = MongoDBObject.newBuilder
        builder += KEY -> name
        builder ++= listBuilder.result
        coll.update(q, builder.result.asDBObject, true, false)

      // new : just add
      case None => 
        val listBuilder = MongoDBList.newBuilder
        listBuilder ++= elements

        val builder = MongoDBObject.newBuilder
        builder += KEY -> name
        builder ++= listBuilder.result
        coll += builder.result.asDBObject
    }
  }

  def updateVectorStorageEntryFor(name: String, index: Int, elem: Array[Byte]) = queryFor(name) { (q, dbo) =>
    dbo += ((index.toString, elem))
    coll.update(q, dbo, true, false)
  }

  def getVectorStorageEntryFor(name: String, index: Int): Array[Byte] = queryFor(name) { (q, dbo) =>
    dbo(index.toString).asInstanceOf[Array[Byte]]
  }

  /**
   * if <tt>start</tt> and <tt>finish</tt> both are defined, ignore <tt>count</tt> and
   * report the range [start, finish)
   * if <tt>start</tt> is not defined, assume <tt>start</tt> = 0
   * if <tt>start</tt> == 0 and <tt>finish</tt> == 0, return an empty collection
   */
  def getVectorStorageRangeFor(name: String, start: Option[Int], finish: Option[Int], count: Int): List[Array[Byte]] = queryFor(name) { (q, dbo) =>
    val ls = dbo.filter { case (k, v) => k != KEY && k != "_id" }
                .toSeq
                .sortWith((e1, e2) => (e1._1.toInt < e2._1.toInt))
                .map(_._2)

    val st = start.getOrElse(0)
    val cnt =
      if (finish.isDefined) {
        val f = finish.get
        if (f >= st) (f - st) else count
      }
      else count
    if (st == 0 && cnt == 0) List()
    ls.slice(st, st + cnt).asInstanceOf[List[Array[Byte]]]
  }

  def getVectorStorageSizeFor(name: String): Int = queryFor(name) { (q, dbo) =>
    dbo.size - 2
  }

  def insertRefStorageFor(name: String, element: Array[Byte]) = {
    // lookup with name
    val q: DBObject = MongoDBObject(KEY -> name)

    coll.findOne(q) match {
      // exists : need to update
      case Some(dbo) => 
        dbo += ((REF, element))
        coll.update(q, dbo, true, false)

      // not found : make one
      case None => 
        val builder = MongoDBObject.newBuilder
        builder += KEY -> name
        builder += REF -> element
        coll += builder.result.asDBObject
    }
  }

  def getRefStorageFor(name: String): Option[Array[Byte]] = try {
    queryFor(name) { (q, dbo) =>
      dbo.get(REF).asInstanceOf[Option[Array[Byte]]]
    }
  } catch {
    case e: java.util.NoSuchElementException => None
  }
}
