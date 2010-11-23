package akka.persistence.couchdb

import akka.stm._
import akka.persistence.common._
import akka.util.Logging
import akka.config.Config.config


import org.apache.commons.httpclient.methods.{GetMethod, PostMethod, PutMethod, DeleteMethod}
import org.apache.commons.httpclient.params.HttpMethodParams
import org.apache.commons.httpclient.methods._
import org.apache.commons.httpclient.{DefaultHttpMethodRetryHandler, HttpClient}

import scala.util.parsing.json._;
import sjson.json._
import DefaultProtocol._



private [akka] object CouchDBStorageBackend extends
  MapStorageBackend[Array[Byte], Array[Byte]] with
  VectorStorageBackend[Array[Byte]] with
  RefStorageBackend[Array[Byte]] with
  Logging {


        import dispatch.json._

        implicit object widgetWrites extends Writes[Map[String,Any]] {
                def writes(o: Map[String,Any]): JsValue = JsValue(o)
        }

        lazy val URL = config.
                   getString("akka.storage.couchdb.url").
                   getOrElse(throw new IllegalArgumentException("'akka.storage.couchdb.url' not found in config"))

  def drop() = {
    val client = new HttpClient()
                val delete = new DeleteMethod(URL)
                client.executeMethod(delete)
  }

  def create() = {
    val client = new HttpClient()
    val put = new PutMethod(URL)
    put.setRequestEntity(new StringRequestEntity("", null, "utf-8"))
    put.setRequestHeader("Content-Type", "application/json")
          client.executeMethod(put)
    put.getResponseBodyAsString
  }

        private def storeMap(name: String, postfix: String, entries: List[(Array[Byte], Array[Byte])]) ={
                var m = entries.map(e=>(new String(e._1) -> new String(e._2))).toMap + ("_id" -> (name + postfix))
                val dataJson = JsonSerialization.tojson(m)
                postData(URL, dataJson.toString)
        }

        private def storeMap(name: String, postfix: String, entries: Map[String, Any]) ={
    postData(URL, JsonSerialization.tojson(entries + ("_id" -> (name + postfix))).toString)
  }

  private def getResponseForNameAsMap(name: String, postfix: String): Option[Map[String, Any]] = {
    getResponse(URL + name + postfix).flatMap(JSON.parseFull(_)).asInstanceOf[Option[Map[String, Any]]]
  }


        def insertMapStorageEntriesFor(name: String, entries: List[(Array[Byte], Array[Byte])]) ={
                val newDoc = getResponseForNameAsMap(name, "_map").getOrElse(Map[String, Any]()) ++
                  entries.map(e => (new String(e._1) -> new String(e._2))).toMap
    storeMap(name, "_map", newDoc)
        }

  def insertMapStorageEntryFor(name: String, key: Array[Byte], value: Array[Byte])={
                insertMapStorageEntriesFor(name, List((key, value)))
        }


  def removeMapStorageFor(name: String) {
    lazy val url = URL + name + "_map"
                findDocRev(name + "_map").foreach(deleteData(url, _))
        }

  def removeMapStorageFor(name: String, key: Array[Byte]): Unit = {
    lazy val sKey = new String(key)
    // if we can't find the map for name, then we don't need to delete it.
                getResponseForNameAsMap(name, "_map").foreach(doc => storeMap(name, "_map", doc - sKey))
        }

  def getMapStorageEntryFor(name: String, key: Array[Byte]): Option[Array[Byte]] = {
    lazy val sKey = new String(key)
    getResponseForNameAsMap(name, "_map").flatMap(_.get(sKey)).asInstanceOf[Option[String]].map(_.getBytes)
        }

        def getMapStorageSizeFor(name: String): Int = getMapStorageFor(name).size

  def getMapStorageFor(name: String): List[(Array[Byte], Array[Byte])] = {
                val m = getResponseForNameAsMap(name, "_map").map(_ - ("_id", "_rev")).getOrElse(Map[String, Any]())
                m.toList.map(e => (e._1.getBytes, e._2.asInstanceOf[String].getBytes))
        }

        def getMapStorageRangeFor(name: String, start: Option[Array[Byte]], finish: Option[Array[Byte]], count: Int): List[(Array[Byte], Array[Byte])] = {
    val m = getResponseForNameAsMap(name, "_map").map(_ - ("_id", "_rev")).getOrElse(Map[String, Any]())
    val keys = m.keys.toList.sortWith(_ < _)

    // if the supplied start is not defined, get the head of keys
    val s = start.map(new String(_)).getOrElse(keys.head)

    // if the supplied finish is not defined, get the last element of keys
    val f = finish.map(new String(_)).getOrElse(keys.last)

    val c = if (count == 0) Int.MaxValue else count
    // slice from keys: both ends inclusive
    val ks = keys.slice(keys.indexOf(s), scala.math.min(keys.indexOf(s) + c, keys.indexOf(f) + 1))
    ks.map(k => (k.getBytes, m(k).asInstanceOf[String].getBytes))
        }

  def insertVectorStorageEntryFor(name: String, element: Array[Byte]) = {
    insertVectorStorageEntriesFor(name, List(element))
  }

  def insertVectorStorageEntriesFor(name: String, elements: List[Array[Byte]]) = {
    val m = getResponseForNameAsMap(name, "_vector").getOrElse(Map[String, Any]())
    val v = elements.map(x =>new String(x)) ::: m.getOrElse("vector", List[String]()).asInstanceOf[List[String]]
    storeMap(name, "_vector", m + ("vector" -> v))
  }

  def updateVectorStorageEntryFor(name: String, index: Int, elem: Array[Byte]) = {
    val m = getResponseForNameAsMap(name, "_vector").getOrElse(Map[String, Any]())
    val v: List[String] = m.getOrElse("vector", List[String]()).asInstanceOf[List[String]]
    if (v.indices.contains(index)) {
      storeMap(name, "_vector", m + ("vector" -> v.updated(index, new String(elem))))
    }
  }

  def getVectorStorageEntryFor(name: String, index: Int): Array[Byte] ={
                val v = getResponseForNameAsMap(name, "_vector").flatMap(_.get("vector")).getOrElse(List[String]()).asInstanceOf[List[String]]
                if (v.indices.contains(index))
                  v(index).getBytes
                else
                  Array[Byte]()
        }

  def getVectorStorageSizeFor(name: String): Int ={
                getResponseForNameAsMap(name, "_vector").flatMap(_.get("vector")).map(_.asInstanceOf[List[String]].size).getOrElse(0)
        }

  def getVectorStorageRangeFor(name: String, start: Option[Int], finish: Option[Int], count: Int): List[Array[Byte]] = {
    val v = getResponseForNameAsMap(name, "_vector").flatMap(_.get("vector")).asInstanceOf[Option[List[String]]].getOrElse(List[String]())
    val s = start.getOrElse(0)
    val f = finish.getOrElse(v.length)
    val c = if (count == 0) v.length else count
    v.slice(s, scala.math.min(s + c, f)).map(_.getBytes)
  }

  def insertRefStorageFor(name: String, element: Array[Byte]) ={
                val newDoc = getResponseForNameAsMap(name, "_ref").getOrElse(Map[String, Any]()) + ("ref" -> new String(element))
                storeMap(name, "_ref", newDoc)
        }

        def getRefStorageFor(name: String): Option[Array[Byte]] ={
                getResponseForNameAsMap(name, "_ref").flatMap(_.get("ref")).map(_.asInstanceOf[String].getBytes)
        }

        private def findDocRev(name: String) = {
                getResponse(URL + name).flatMap(JSON.parseFull(_)).asInstanceOf[Option[Map[String, Any]]]
                .flatMap(_.get("_rev")).asInstanceOf[Option[String]]
        }

        private def deleteData(url:String, rev:String): Option[String] = {
                val client = new HttpClient()
                val delete = new DeleteMethod(url)
                delete.setRequestHeader("If-Match", rev)
                client.executeMethod(delete)

                val response = delete.getResponseBodyAsString()
                if (response != null)
                  Some(response)
                else
                  None
        }

        private def postData(url: String, data: String): Option[String] = {
        val client = new HttpClient()
        val post = new PostMethod(url)
        post.setRequestEntity(new StringRequestEntity(data, null, "utf-8"))
        post.setRequestHeader("Content-Type", "application/json")
        client.executeMethod(post)
    val response = post.getResponseBodyAsString
    if (response != null)
          Some(response)
        else
          None
        }

        private def getResponse(url: String): Option[String] = {
        val client = new HttpClient()
        val method = new GetMethod(url)

        method.getParams().setParameter(HttpMethodParams.RETRY_HANDLER,
    new DefaultHttpMethodRetryHandler(3, false))

        client.executeMethod(method)
    val response = method.getResponseBodyAsString
        if (method.getStatusCode == 200 && response != null)
        Some(response)
        else
        None
        }
}

