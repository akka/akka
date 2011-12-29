package akka.camel

import akka.actor.{Props, ActorSystem, ActorRef, Actor}


object Migration{
  def allActors : Seq[ActorRef] = unsupported

  def actorOf(actor: Actor)(implicit as: ActorSystem) : ActorRef = as.actorOf(Props(actor))
  trait Bootable {
    def onLoad = {}
    def onUnload = {}
  }

  val registry = new {
    def addListener(a: ActorRef) : Unit = unsupported
    def removeListener(a: ActorRef) : Unit = unsupported
  }

  val config = new {
    def isModuleEnabled(s:String):Boolean = {

//      akka.config.Config.config.getList("akka.enabled-modules").exists(_ == "camel")
      unsupported
    }
  }
  def unsupported = throw new UnsupportedOperationException
  val EventHandler = new {
    def notifyListeners(a:Any) : Unit = unsupported
    def Info(a: Any) : Unit = unsupported
    def debug(a: Any) : Unit = unsupported
  }
}