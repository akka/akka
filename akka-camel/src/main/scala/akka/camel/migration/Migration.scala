package akka.camel.migration

//TODO remove when all the contents gets migrated
object Migration {

  val EventHandler = new {
    def notifyListeners(a: Any): Unit = println(a)
    def Info(a: Any): Unit = { println(a); a }
    def debug(a: Any): Unit = println("DEBUG>>" + a)
  }
}