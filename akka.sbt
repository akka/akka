ThisBuild / resolvers += "lightbend-akka".at("https://dl.cloudsmith.io/basic/lightbend/akka/maven/")
ThisBuild / credentials ++= {
  val path = Path.userHome / ".sbt" / ".credentials"
  if(path.isFile) {
    Seq(Credentials(path))
  } else Nil
}
