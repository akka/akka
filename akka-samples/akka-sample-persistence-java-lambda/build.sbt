name := "akka-sample-persistence-java-lambda"

version := "2.5-SNAPSHOT"

scalaVersion := "2.11.8"

javacOptions in compile ++= Seq("-encoding", "UTF-8", "-source", "1.8", "-target", "1.8", "-Xlint")

javacOptions in doc ++= Seq("-encoding", "UTF-8", "-source", "1.8", "-Xdoclint:none")

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-persistence" % "2.5-SNAPSHOT",
  "org.iq80.leveldb" % "leveldb" % "0.7",
  "org.fusesource.leveldbjni" % "leveldbjni-all" % "1.8"
)

licenses := Seq(("CC0", url("http://creativecommons.org/publicdomain/zero/1.0")))
