name := "akka-sample-camel-java"

version := "2.4.14"

scalaVersion := "2.11.7"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-camel" % "2.4.14",
  "org.apache.camel" % "camel-jetty" % "2.10.3",
  "org.apache.camel" % "camel-quartz" % "2.10.3",
  "org.slf4j" % "slf4j-api" % "1.7.2",
  "ch.qos.logback" % "logback-classic" % "1.0.7"
)

licenses := Seq(("CC0", url("http://creativecommons.org/publicdomain/zero/1.0")))
