name := "akka-sample-camel-scala"

version := "2.3.10"

scalaVersion := "2.10.4"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-camel" % "2.3.10",
  "org.apache.camel" % "camel-jetty" % "2.10.3",
  "org.apache.camel" % "camel-quartz" % "2.10.3",
  "org.slf4j" % "slf4j-api" % "1.7.2",
  "ch.qos.logback" % "logback-classic" % "1.0.7"
)

