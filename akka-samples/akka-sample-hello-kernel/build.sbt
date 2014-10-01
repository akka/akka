import NativePackagerKeys._

packageArchetype.akka_application

name := """hello-kernel"""

mainClass in Compile := Some("sample.kernel.hello.HelloKernel")

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-kernel" % "2.4-SNAPSHOT",
  "com.typesafe.akka" %% "akka-actor" % "2.4-SNAPSHOT"
)
