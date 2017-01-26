name := "akka-sample-persistence-scala"

version := "2.5-M1"

scalaVersion := "2.11.8"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.5-M1",
  "com.typesafe.akka" %% "akka-persistence" % "2.5-M1",
  "org.iq80.leveldb"            % "leveldb"          % "0.7",
  "org.fusesource.leveldbjni"   % "leveldbjni-all"   % "1.8"
)

licenses := Seq(("CC0", url("http://creativecommons.org/publicdomain/zero/1.0")))
