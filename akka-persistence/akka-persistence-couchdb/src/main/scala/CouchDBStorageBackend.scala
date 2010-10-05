package se.scalablesolutions.akka.persistence.couchdb

import se.scalablesolutions.akka.stm._
import se.scalablesolutions.akka.persistence.common._
import se.scalablesolutions.akka.util.Logging
import se.scalablesolutions.akka.config.Config.config


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

	val URL = config.getString("akka.storage.couchdb.url", "http://localhost:5984/testakka/")

  def drop() = {
    val client: HttpClient = new HttpClient()
		val delete: DeleteMethod = new DeleteMethod(URL)
		client.executeMethod(delete)
  }
  
  def create() = {
    val client: HttpClient = new HttpClient()
    val put: PutMethod = new PutMethod(URL)
    put.setRequestEntity(new StringRequestEntity("", null, "utf-8"))
    put.setRequestHeader("Content-Type", "application/json")
	  client.executeMethod(put)
    put.getResponseBodyAsString
  }
  
	private def storeMap(name: String, entries: List[(Array[Byte], Array[Byte])]) ={
		var m = entries.map(e=>(new String(e._1) -> new String(e._2))).toMap + ("_id" -> name)
		val dataJson = JsonSerialization.tojson(m)
		postData(URL, dataJson.toString)
	}
	
	private def storeMap(name: String, entries: Map[String, Any]) ={
    postData(URL, JsonSerialization.tojson(entries + ("_id" -> name)).toString)
  }	

	def insertMapStorageEntriesFor(name: String, entries: List[(Array[Byte], Array[Byte])]) ={
		val newDoc = getResponseForNameAsMap(name).getOrElse(Map[String, Any]()) ++ 
		  entries.map(e=>(new String(e._1) -> new String(e._2))).toMap
    storeMap(name, newDoc)
	}
	
  def insertMapStorageEntryFor(name: String, key: Array[Byte], value: Array[Byte])={
		insertMapStorageEntriesFor(name, List((key, value)))
	}
	
	
  def removeMapStorageFor(name: String): Unit = {
		findDocRev(name).flatMap(deleteData(URL + name, _))
	}
	
  def removeMapStorageFor(name: String, key: Array[Byte]): Unit = {
		getResponseForNameAsMap(name).flatMap(doc=>{ // if we can't find the map for name, then we don't need to delete it.
  		removeMapStorageFor(name)
    	storeMap(name, doc - new String(key))
    })
	}
	
  def getMapStorageEntryFor(name: String, key: Array[Byte]): Option[Array[Byte]] = {
    getResponseForNameAsMap(name).flatMap(_.get(new String(key))).asInstanceOf[Option[String]]
      .map(_.getBytes)
	}
  
	def getMapStorageSizeFor(name: String): Int = {
		getMapStorageFor(name).size
	}
	
  def getMapStorageFor(name: String): List[(Array[Byte], Array[Byte])] = {
		val m = getResponseForNameAsMap(name).map(_ - ("_id", "_rev")).getOrElse(Map[String, Any]())
		m.toList.map(e => (e._1.getBytes, e._2.asInstanceOf[String].getBytes))
	}
  
	def getMapStorageRangeFor(name: String, start: Option[Array[Byte]], finish: Option[Array[Byte]], count: Int): List[(Array[Byte], Array[Byte])] = {
    val m = getResponseForNameAsMap(name).map(_ - ("_id", "_rev")).getOrElse(Map[String, Any]())
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
    val m = getResponseForNameAsMap(name).getOrElse(Map[String, Any]())
    val v = elements.map(x=>new String(x)) ::: m.getOrElse("vector", List[String]()).asInstanceOf[List[String]]
    storeMap(name, m + ("vector" -> v))
  }

  def updateVectorStorageEntryFor(name: String, index: Int, elem: Array[Byte]) = {
    val m = getResponseForNameAsMap(name).getOrElse(Map[String, Any]())
    val v: List[String] = m.getOrElse("vector", List[String]()).asInstanceOf[List[String]]
    if (v.indices.contains(index)) {
      storeMap(name, m + ("vector" -> v.updated(index, new String(elem))))
    }
  }
  
  def getVectorStorageEntryFor(name: String, index: Int): Array[Byte] ={
		val v = getResponseForNameAsMap(name).flatMap(_.get("vector")).getOrElse(List[String]()).asInstanceOf[List[String]]
		if (v.indices.contains(index))
		  v(index).getBytes
		else
		  Array[Byte]()
	}
	
  def getVectorStorageSizeFor(name: String): Int ={
		getResponseForNameAsMap(name).flatMap(_.get("vector")).map(_.asInstanceOf[List[String]].size).getOrElse(0)
	}

  def getVectorStorageRangeFor(name: String, start: Option[Int], finish: Option[Int], count: Int): List[Array[Byte]] = {
    val v = getResponseForNameAsMap(name).flatMap(_.get("vector")).asInstanceOf[Option[List[String]]].getOrElse(List[String]())
    val s = start.getOrElse(0)
    val f = finish.getOrElse(v.length)
    val c = if (count == 0) v.length else count
    v.slice(s, scala.math.min(s + c, f)).map(_.getBytes)
  }
	
  def insertRefStorageFor(name: String, element: Array[Byte]) ={
		val newDoc = getResponseForNameAsMap(name).getOrElse(Map[String, Any]()) ++ Map("ref" -> new String(element))
		storeMap(name, newDoc)
	}
	
	def getRefStorageFor(name: String): Option[Array[Byte]] ={
		getResponseForNameAsMap(name).flatMap(_.get("ref")).map(_.asInstanceOf[String].getBytes)
	}

	private def findDocRev(name: String) = {
		getResponse(URL + name).flatMap(JSON.parseFull(_)).asInstanceOf[Option[Map[String, Any]]]
		.flatMap(_.get("_rev")).asInstanceOf[Option[String]]
	}

		private def deleteData(url:String, rev:String): Option[String] = {
			val client: HttpClient = new HttpClient()
			val delete: DeleteMethod = new DeleteMethod(url)
			delete.setRequestHeader("If-Match", rev)
			client.executeMethod(delete)
			
			val response = delete.getResponseBodyAsString()
			if (response != null)
			  Some(response)
			else
			  None
		}
	
		private def postData(url: String, data: String): Option[String] = {
	  	val client: HttpClient = new HttpClient()
	  	val post: PostMethod = new PostMethod(url)
	  	post.setRequestEntity(new StringRequestEntity(data, null, "utf-8"))
	  	post.setRequestHeader("Content-Type", "application/json")
	  	client.executeMethod(post)
	    val response = post.getResponseBodyAsString
	    if (response != null)
	  	  Some(response)
	  	else
	  	  None
		}

  private def getResponseForNameAsMap(name: String): Option[Map[String, Any]] = {
    getResponse(URL + name).flatMap(JSON.parseFull(_)).asInstanceOf[Option[Map[String, Any]]]
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

