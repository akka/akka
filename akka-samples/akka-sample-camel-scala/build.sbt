name := "akka-sample-camel-scala"

version := "15v01p02"

// scalaVersion := provided by Typesafe Reactive Platform

libraryDependencies ++= Seq(
  TypesafeLibrary.akkaCamel.value,
  "org.apache.camel" % "camel-jetty" % "2.10.3",
  "org.apache.camel" % "camel-quartz" % "2.10.3",
  "org.slf4j" % "slf4j-api" % "1.7.2",
  "ch.qos.logback" % "logback-classic" % "1.0.7"
)

