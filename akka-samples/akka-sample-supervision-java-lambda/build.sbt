name := "akka-sample-supervision-java-lambda"

version := "2.4.14"

scalaVersion := "2.11.7"

javacOptions in compile ++= Seq("-encoding", "UTF-8", "-source", "1.8", "-target", "1.8", "-Xlint")

javacOptions in doc ++= Seq("-encoding", "UTF-8", "-source", "1.8", "-Xdoclint:none")

testOptions += Tests.Argument(TestFrameworks.JUnit, "-v", "-a")

libraryDependencies ++= Seq(
  "com.typesafe.akka" %%      "akka-actor" % "2.4.14",
  "com.typesafe.akka" %%    "akka-testkit" % "2.4.14" % "test",
              "junit"  %           "junit" % "4.12"         % "test",
       "com.novocode"  % "junit-interface" % "0.11"         % "test")

licenses := Seq(("CC0", url("http://creativecommons.org/publicdomain/zero/1.0")))
