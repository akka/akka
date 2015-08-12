name := "akka-sample-persistence-java-lambda"

version := "1.0"

scalaVersion := "2.11.6"

javacOptions in compile ++= Seq("-encoding", "UTF-8", "-source", "1.8", "-target", "1.8", "-Xlint")

javacOptions in doc ++= Seq("-encoding", "UTF-8", "-source", "1.8", "-Xdoclint:none")

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-persistence" % "2.4-SNAPSHOT"
)

