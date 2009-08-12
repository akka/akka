package se.scalablesolutions.akka.kernel.state

import com.mongodb._
import se.scalablesolutions.akka.kernel.util.Logging
import serialization.{Serializer}

import java.util.{Map=>JMap, List=>JList, ArrayList=>JArrayList}

object MongoStorage extends MapStorage 
    with VectorStorage with Logging {
      
  // enrich with null safe findOne
  class RichDBCollection(value: DBCollection) {
    def findOneNS(o: DBObject): Option[DBObject] = {
      value.findOne(o) match {
        case null => None
        case x => Some(x)
      }
    }
  }
  
  implicit def enrichDBCollection(c: DBCollection) = new RichDBCollection(c)
  
  val KEY = "key"
  val VALUE = "value"
  val db = new Mongo("mydb"); // @fixme: need to externalize
  val COLLECTION = "akka_coll"
  val coll = db.getCollection(COLLECTION)
  
  // @fixme: make this pluggable
  private[this] val serializer: Serializer = Serializer.ScalaJSON
  
  override def insertMapStorageEntryFor(name: String, key: AnyRef, value: AnyRef) {
    insertMapStorageEntriesFor(name, List((key, value)))
  }

  override def insertMapStorageEntriesFor(name: String, entries: List[Tuple2[AnyRef, AnyRef]]) {
    import java.util.{Map, HashMap}
    
    val m: Map[AnyRef, AnyRef] = new HashMap
    for ((k, v) <- entries) {
      m.put(k, serializer.out(v))
    }
    
    nullSafeFindOne(name) match {
      case None => 
        coll.insert(new BasicDBObject().append(KEY, name).append(VALUE, m))
      case Some(dbo) => {
        // collate the maps
        val o = dbo.get(VALUE).asInstanceOf[Map[AnyRef, AnyRef]]
        o.putAll(m)
        
        // remove existing reference
        removeMapStorageFor(name)
        // and insert
        coll.insert(new BasicDBObject().append(KEY, name).append(VALUE, o))
      }
    }
  }
  
  override def removeMapStorageFor(name: String) = {
    val q = new BasicDBObject
    q.put(KEY, name)
    coll.remove(q)
  }

  override def removeMapStorageFor(name: String, key: AnyRef) = {
  }
  
  override def getMapStorageEntryFor(name: String, key: AnyRef): Option[AnyRef] = {
    try {
      getValueForKey(name, key.asInstanceOf[String])
    } catch {
      case e =>
        e.printStackTrace
        None
    }
  }
  
  override def getMapStorageSizeFor(name: String): Int = {
    nullSafeFindOne(name) match {
      case None => 0
      case Some(dbo) =>
        dbo.get(VALUE).asInstanceOf[JMap[String, AnyRef]].keySet.size
    }
  }
  
  override def getMapStorageFor(name: String): List[Tuple2[AnyRef, AnyRef]]  = {
    val m = 
      nullSafeFindOne(name) match {
        case None => 
          throw new Predef.NoSuchElementException(name + " not present")
        case Some(dbo) =>
          dbo.get(VALUE).asInstanceOf[JMap[String, AnyRef]]
      }
    val n = 
      List(m.keySet.toArray: _*).asInstanceOf[List[String]]
    val vals = 
      for(s <- n) 
        yield (s, serializer.in(m.get(s).asInstanceOf[Array[Byte]], None))
    vals.asInstanceOf[List[Tuple2[String, AnyRef]]]
  }
  
  override def getMapStorageRangeFor(name: String, start: Option[AnyRef], 
                            finish: Option[AnyRef], 
                            count: Int): List[Tuple2[AnyRef, AnyRef]] = {
    val m = 
      nullSafeFindOne(name) match {
        case None => 
          throw new Predef.NoSuchElementException(name + " not present")
        case Some(dbo) =>
          dbo.get(VALUE).asInstanceOf[JMap[String, AnyRef]]
      }
    val s = start.get.asInstanceOf[Int]
    val n = 
      List(m.keySet.toArray: _*).asInstanceOf[List[String]].slice(s, s + count)
    val vals = 
      for(s <- n) 
        yield (s, serializer.in(m.get(s).asInstanceOf[Array[Byte]], None))
    vals.asInstanceOf[List[Tuple2[String, AnyRef]]]
  }
  
  private def getValueForKey(name: String, key: String): Option[AnyRef] = {
    try {
      nullSafeFindOne(name) match {
        case None => None
        case Some(dbo) =>
          Some(serializer.in(
            dbo.get(VALUE)
               .asInstanceOf[JMap[String, AnyRef]]
               .get(key).asInstanceOf[Array[Byte]], None))
      }
    } catch {
      case e =>
        throw new Predef.NoSuchElementException(e.getMessage)
    }
  }
  
  def insertVectorStorageEntriesFor(name: String, elements: List[AnyRef]) = {
    val q = new BasicDBObject
    q.put(KEY, name)
    
    val currentList =
      coll.findOneNS(q) match {
        case None => 
          new JArrayList[AnyRef]
        case Some(dbo) => 
          dbo.get(VALUE).asInstanceOf[JArrayList[AnyRef]]
      }
    if (!currentList.isEmpty) {
      // record exists
      // remove before adding
      coll.remove(q)
    }
    
    // add to the current list
    elements.map(serializer.out(_)).foreach(currentList.add(_))
    
    coll.insert(
      new BasicDBObject()
        .append(KEY, name)
        .append(VALUE, currentList)
    )
  }
  
  override def insertVectorStorageEntryFor(name: String, element: AnyRef) = {
    insertVectorStorageEntriesFor(name, List(element))
  }
  
  override def getVectorStorageEntryFor(name: String, index: Int): AnyRef = {
    try {
      val o =
      nullSafeFindOne(name) match {
        case None => 
          throw new Predef.NoSuchElementException(name + " not present")

        case Some(dbo) =>
          dbo.get(VALUE).asInstanceOf[JList[AnyRef]]
      }
      serializer.in(
        o.get(index).asInstanceOf[Array[Byte]], 
        None
      )
    } catch {
      case e => 
        throw new Predef.NoSuchElementException(e.getMessage)
    }
  }
  
  override def getVectorStorageRangeFor(name: String, start: Option[Int], finish: Option[Int], count: Int): List[AnyRef] = {
    try {
      val o =
      nullSafeFindOne(name) match {
        case None => 
          throw new Predef.NoSuchElementException(name + " not present")

        case Some(dbo) =>
          dbo.get(VALUE).asInstanceOf[JList[AnyRef]]
      }

      // pick the subrange and make a Scala list
      val l = 
        List(o.subList(start.get, start.get + count).toArray: _*)

      for(e <- l) 
        yield serializer.in(e.asInstanceOf[Array[Byte]], None)
    } catch {
      case e => 
        throw new Predef.NoSuchElementException(e.getMessage)
    }
  }
  
  override def getVectorStorageSizeFor(name: String): Int = {
    nullSafeFindOne(name) match {
      case None => 0
      case Some(dbo) => 
        dbo.get(VALUE).asInstanceOf[JList[AnyRef]].size
    }
  }

  private def nullSafeFindOne(name: String): Option[DBObject] = {
    val o = new BasicDBObject
    o.put(KEY, name)
    coll.findOneNS(o)
  }
}
