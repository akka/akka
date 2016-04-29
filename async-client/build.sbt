name := "async-client"

organization := "async-client"

version := "0.1-SNAPSHOT"

scalaVersion := "2.11.7"

val AkkaVersion = "2.4.0"

resolvers += Resolver.typesafeRepo("releases")

libraryDependencies += "com.typesafe.akka" %% "akka-actor" % AkkaVersion

libraryDependencies += "org.asynchttpclient" % "async-http-client" % "2.0.2"
