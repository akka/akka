import akka.actor.{ Props, ActorSystem }
import akka.camel.{ CamelMessage, Consumer }
import java.io.File
import org.apache.camel.Exchange

object SimpleFileConsumer extends App {
  val subDir = "consume-files"
  val tmpDir = System.getProperty("java.io.tmpdir")
  val consumeDir = new File(tmpDir, subDir)
  consumeDir.mkdirs()
  val tmpDirUri = "file://%s/%s" format (tmpDir, subDir)

  val system = ActorSystem("consume-files")
  val fileConsumer = system.actorOf(Props(new FileConsumer(tmpDirUri)), "fileConsumer")
  println(String.format("Put a text file in '%s', the consumer will pick it up!", consumeDir))
}

class FileConsumer(uri: String) extends Consumer {
  def endpointUri = uri
  def receive = {
    case msg: CamelMessage ⇒
      println("Received file %s with content:\n%s".format(msg.headers(Exchange.FILE_NAME), msg.bodyAs[String]))
  }
}
