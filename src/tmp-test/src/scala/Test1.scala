import akka.actor.{Props, ActorSystem}

import java.io.FileWriter

object RichString{
  implicit def toRichString(s:String) : RichString = new RichString(s)
}
class RichString(s: String){
  def saveAs(fileName: String) = write(fileName, s)
  def >>(fileName: String) = this.saveAs(fileName)
  def <<(content: String) = write(s, content)

  private[this] def write(fileName: String, content: String) {
    val f = new FileWriter(fileName)
    f.write(content)
    f.close()
  }
}

import RichString._

object Test1 extends App{
  import akka.actor.Actor
  import java.lang.String
  import akka.camel.Message

  class CamelConsumer extends Actor with akka.camel.Consumer{
    from("file://data/input/CamelConsumer")

    protected def receive = {
      case msg : Message =>{
        printf("Received '%s'\n", msg.bodyAs[String] )
      }
    }
  }

  akka.camel.CamelServiceManager.startCamelService
  ActorSystem("test1").actorOf(Props[CamelConsumer])

  "data/input/CamelConsumer/file1.txt" << "test data 1"

  Thread.sleep(10000)

}