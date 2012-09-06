package akka.camel

import org.scalatest.matchers.MustMatchers
import org.scalatest.WordSpec
import akka.camel.TestSupport._
import org.apache.http.util.EntityUtils
import java.io.{ IOException, File }
import org.apache.http.client.methods.HttpPost
import org.apache.http.impl.client.DefaultHttpClient
import org.apache.http.entity.mime.MultipartEntity
import org.apache.http.entity.mime.content.{ FileBody, StringBody }
import org.apache.commons.io.{ IOUtils, FileUtils }
import concurrent.{ Promise, Await }
import akka.util.Timeout
import scala.concurrent.util.duration._
import language.postfixOps
import org.apache.camel.Exchange
import akka.actor.{ Props, Actor, ActorRef }
import util.{ Failure, Success }

class MultipartUploadTest extends WordSpec with MustMatchers with SharedCamelSystem {

  "A multipart/form-data POST to a camel-jetty Consumer" must {
    "result in a message sent to the consumer including attachments" in {
      implicit val ec = system.dispatcher
      implicit val timeout = 10 seconds
      val promise = Promise[List[Attachment]]()
      val future = promise.future

      val ref = start(new JettyConsumer(promise), "jetty-consumer")
      val text = "some text data"
      val textAttachment = createTmpFile("text-attachment.txt", text)
      val xml = """<?xml version="1.0" encoding="UTF-8"?><element>some other data</element>"""
      val xmlAttachment = createTmpFile("xml-attachment.xml", xml)
      uploadFiles("http://localhost:8875/upload", List(xmlAttachment, textAttachment))

      val attachments = Await.result(future, 10 seconds)
      attachments.size must be(2)
      attachments.filter(a ⇒ a.name == "text-attachment.txt" && a.contentType == "text/plain" && a.bytes.decodeString("UTF8") == text).size must be(1)
      attachments.filter(a ⇒ a.name == "xml-attachment.xml" && a.contentType == "application/octet-stream" && a.bytes.decodeString("UTF8") == xml).size must be(1)

      stop(ref)
      camel.context.removeComponent("jetty")
    }
  }

  def createTmpFile(name: String, data: String): File = {
    val file = new File(FileUtils.getTempDirectory, name)
    FileUtils.write(file, data)
    file
  }

  def uploadFiles(url: String, files: List[File]) {
    val http = new DefaultHttpClient()
    try {
      val post = new HttpPost(url)
      val reqEntity = new MultipartEntity()
      files.foreach(f ⇒ reqEntity.addPart(f.getName, new FileBody(f)))
      post.setEntity(reqEntity)
      val response = http.execute(post)
      val resEntity = response.getEntity
      EntityUtils.consume(resEntity)
    } finally {
      http.getConnectionManager.shutdown()
      if (files.map(_.delete()).exists(!_)) throw new IOException("temporary files where not deleted")
    }
  }
}

class JettyConsumer(promise: Promise[List[Attachment]]) extends Consumer {
  def endpointUri = "jetty://http://localhost:8875/upload"
  def receive = {
    case msg: CamelMessage ⇒
      val headers = Map(Exchange.HTTP_RESPONSE_CODE -> "200")
      sender ! CamelMessage("OK", headers)
      promise.success(msg.attachments.values.toList)
  }
}
