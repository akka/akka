import sbt._

class AkkaParent(info: ProjectInfo) extends ParentProject(info) {
     lazy val javautil = project("akka-util-java", "akka java util")
     lazy val util = project("akka-util", "akka util")
     lazy val core = project("akka-core", "akka core", util,javautil)
}
