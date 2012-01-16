package akka.camel.migration


object Migration{

  trait Bootable {
    def onLoad = {}
    def onUnload = {}
  }

  val config = new {
    def isModuleEnabled(s:String):Boolean = {

//      akka.config.Config.config.getList("akka.enabled-modules").exists(_ == "camel")
      true
    }
  }
  def unsupported = throw new UnsupportedOperationException
  val EventHandler = new {
    def notifyListeners(a:Any) : Unit = println(a)
    def Info(a: Any) : Unit = {println(a); a }
    def debug(a: Any) : Unit = println("DEBUG>>"+a)
  }
}