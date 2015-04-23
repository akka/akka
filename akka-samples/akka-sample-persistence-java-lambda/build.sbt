name := "akka-sample-persistence-java-lambda"

version := "1.0"

scalaVersion := "2.10.4"

javacOptions ++= Seq("-source", "1.8", "-target", "1.8", "-Xlint")

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-persistence-experimental" % "2.3.10"
)

