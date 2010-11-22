/**
 *  Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.persistence.simpledb

import akka.persistence.common._
import akka.config.Config.config
import java.lang.String
import java.util.{List => JList, ArrayList => JAList}

import collection.immutable.{HashMap, Iterable}
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.services.simpledb.AmazonSimpleDBClient
import com.amazonaws.services.simpledb.model._
import collection.{JavaConversions, Map}
import collection.mutable.{ArrayBuffer, HashMap => MMap}
import com.amazonaws.{Protocol, ClientConfiguration}

private[akka] object SimpledbStorageBackend extends CommonStorageBackend {

  import org.apache.commons.codec.binary.Base64
  import KVStorageBackend._

  val seperator = "\r\n"
  val seperatorBytes = seperator.getBytes("UTF-8")
  val sizeAtt = "size"
  val ownerAtt = "owner"
  val base64 = new Base64(1024, seperatorBytes, true)
  val base64key = new Base64(1024, Array.empty[Byte], true)
  val id = config.getString("akka.persistence.simpledb.account.id").getOrElse{
    val e = new IllegalStateException("You must provide an AWS id")
    log.error(e, "You Must Provide an AWS id to use the SimpledbStorageBackend")
    throw e
  }
  val secretKey = config.getString("akka.persistence.simpledb.account.secretKey").getOrElse{
    val e = new IllegalStateException("You must provide an AWS secretKey")
    log.error(e, "You Must Provide an AWS secretKey to use the SimpledbStorageBackend")
    throw e
  }
  val refDomain = config.getString("akka.persistence.simpledb.domain.ref", "ref")
  val mapDomain = config.getString("akka.persistence.simpledb.domain.map", "map")
  val queueDomain = config.getString("akka.persistence.simpledb.domain.queue", "queue")
  val vectorDomain = config.getString("akka.persistence.simpledb.domain.vector", "vector")
  val credentials = new BasicAWSCredentials(id, secretKey);
  val clientConfig = new ClientConfiguration()
  for (i <- config.getInt("akka.persistence.simpledb.client.timeout")) {
    clientConfig.setConnectionTimeout(i)
  }
  for (i <- config.getInt("akka.persistence.simpledb.client.maxconnections")) {
    clientConfig.setMaxConnections(i)
  }
  clientConfig.setMaxErrorRetry(config.getInt("akka.persistence.simpledb.client.maxretries", 10))

  for (s <- config.getString("akka.persistence.simpledb.client.protocol")) {
    clientConfig.setProtocol(Protocol.valueOf(s))
  }
  for (i <- config.getInt("akka.persistence.simpledb.client.sockettimeout")) {
    clientConfig.setSocketTimeout(i)
  }
  for {s <- config.getInt("akka.persistence.simpledb.client.sendbuffer")
       r <- config.getInt("akka.persistence.simpledb.client.receivebuffer")} {
    clientConfig.setSocketBufferSizeHints(s, r)
  }

  for (s <- config.getString("akka.persistence.simpledb.client.useragent")) {
    clientConfig.setUserAgent(s)
  }

  val client = new AmazonSimpleDBClient(credentials, clientConfig)

  def queueAccess = queue

  def mapAccess = map

  def vectorAccess = vector

  def refAccess = ref

  val queue = new SimpledbAccess(queueDomain)

  val map = new SimpledbAccess(mapDomain)

  val vector = new SimpledbAccess(vectorDomain)

  val ref = new SimpledbAccess(refDomain)

  private[akka] class SimpledbAccess(val domainName: String) extends KVStorageBackendAccess {
    var created = false

    def getClient(): AmazonSimpleDBClient = {
      if (!created) {
        client.createDomain(new CreateDomainRequest(domainName))
        created = true
      }
      client
    }


    def drop(): Unit = {
      created = false
      client.deleteDomain(new DeleteDomainRequest(domainName))
    }

    def delete(key: Array[Byte]): Unit = getClient.deleteAttributes(new DeleteAttributesRequest(domainName, encodeAndValidateKey(key)))

    override def getAll(keys: Iterable[Array[Byte]]): Map[Array[Byte], Array[Byte]] = {

      var map = new HashMap[Array[Byte], Array[Byte]]

      GetBatcher(domainName, 20).addItems(keys).getRequests foreach {
        req => {
          var res = getClient.select(req)
          var continue = true
          do {
            JavaConversions.asIterable(res.getItems) foreach {
              item => map += (base64key.decode(item.getName) -> recomposeValue(item.getAttributes).get)
            }
            if (res.getNextToken ne null) {
              res = getClient.select(req.withNextToken(res.getNextToken))
            } else {
              continue = false
            }
          } while (continue == true)
        }
      }
      map
    }

    case class GetBatcher(domain: String, maxItems: Int) {

      val reqs = new ArrayBuffer[SelectRequest]
      var currentItems = new ArrayBuffer[String]
      var items = 0

      def addItems(items: Iterable[Array[Byte]]): GetBatcher = {
        items foreach(addItem(_))
        this
      }

      def addItem(item: Array[Byte]) = {
        if ((items + 1 > maxItems)) {
          addReq
        }
        currentItems += (encodeAndValidateKey(item))
        items += 1
      }

      private def addReq() {
        items = 0
        reqs += new SelectRequest(select, true)
        currentItems = new ArrayBuffer[String]
      }

      def getRequests() = {
        if (items > 0) {
          addReq
        }
        reqs
      }


      def select(): String = {
        val in = currentItems.reduceLeft[String] {
          (acc, key) => {
            acc + "', '" + key
          }
        }

        "select * from " + domainName + " where itemName() in ('" + in + "')"
      }

    }


    def get(key: Array[Byte]) = get(key, null)

    def get(key: Array[Byte], default: Array[Byte]): Array[Byte] = {
      val req = new GetAttributesRequest(domainName, encodeAndValidateKey(key)).withConsistentRead(true)
      val resp = getClient.getAttributes(req)
      recomposeValue(resp.getAttributes) match {
        case Some(value) => value
        case None => default
      }
    }


    override def put(key: Array[Byte], value: Array[Byte]) = {
      val req = new PutAttributesRequest(domainName, encodeAndValidateKey(key), decomposeValue(value))
      getClient.putAttributes(req)
    }


    override def putAll(owner: String, keyValues: Iterable[(Array[Byte], Array[Byte])]) = {
      val items = keyValues.foldLeft(new ArrayBuffer[ReplaceableItem]()) {
        (jal, kv) => kv match {
          case (key, value) => {
            jal += (new ReplaceableItem(encodeAndValidateKey(getKey(owner, key)), decomposeValue(value)))
          }
        }
      }

      PutBatcher(domainName, 25, 1000).addItems(items).getRequests foreach (getClient.batchPutAttributes(_))

    }


    case class PutBatcher(domain: String, maxItems: Int, maxAttributes: Int) {

      val reqs = new ArrayBuffer[BatchPutAttributesRequest]
      var currentItems = new JAList[ReplaceableItem]()
      var items = 0
      var atts = 0

      def addItems(items: Seq[ReplaceableItem]): PutBatcher = {
        items foreach(addItem(_))
        this
      }

      def addItem(item: ReplaceableItem) = {
        if ((items + 1 > maxItems) || (atts + item.getAttributes.size > maxAttributes)) {
          addReq
        }
        currentItems.add(item)
        items += 1
        atts += item.getAttributes.size
      }

      private def addReq() {
        items = 0
        atts = 0
        reqs += new BatchPutAttributesRequest(domain, currentItems)
        currentItems = new JAList[ReplaceableItem]()
      }

      def getRequests() = {
        if (items > 0) {
          addReq
        }
        reqs
      }

    }

    def encodeAndValidateKey(key: Array[Byte]): String = {
      val keystr = base64key.encodeToString(key)
      if (keystr.size > 1024) {
        throw new IllegalArgumentException("encoded key was longer than 1024 bytes (or 768 bytes unencoded)")
      }
      keystr
    }

    def decomposeValue(value: Array[Byte]): JList[ReplaceableAttribute] = {
      val encoded = base64.encodeToString(value)
      val strings = encoded.split(seperator)
      if (strings.size > 255) {
        throw new IllegalArgumentException("The decomposed value is larger than 255K (or 195840 bytes unencoded)")
      }

      val list: JAList[ReplaceableAttribute] = strings.zipWithIndex.foldLeft(new JAList[ReplaceableAttribute]) {
        (list, zip) => {
          zip match {
            case (encode, index) => {
              list.add(new ReplaceableAttribute(index.toString, encode, true))
              list
            }
          }
        }
      }
      list.add(new ReplaceableAttribute(sizeAtt, list.size.toString, true))
      list
    }

    def recomposeValue(atts: JList[Attribute]): Option[Array[Byte]] = {
      val itemSnapshot = JavaConversions.asIterable(atts).foldLeft(new MMap[String, String]) {
        (map, att) => {
          map += (att.getName -> att.getValue)
        }
      }
      itemSnapshot.get(sizeAtt) match {
        case Some(strSize) => {
          val size = Integer.parseInt(strSize)
          val encoded = (0 until size).map(_.toString).map(itemSnapshot.get(_).get).reduceLeft[String] {
            (acc, str) => acc + seperator + str
          }
          Some(base64.decode(encoded))
        }
        case None => None
      }
    }

  }


}
