name := "akka-sample-camel-java"

version := "2.4-SNAPSHOT"

scalaVersion := "2.11.8"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-camel" % "2.4-SNAPSHOT",
  "org.apache.camel" % "camel-jetty" % "2.13.4",
  "org.apache.camel" % "camel-quartz" % "2.13.4",
  "org.slf4j" % "slf4j-api" % "1.7.16",
  "ch.qos.logback" % "logback-classic" % "1.1.3"
)

licenses := Seq(("CC0", url("http://creativecommons.org/publicdomain/zero/1.0")))
