name := "akka-supervision-java-lambda"

version := "1.0"

scalaVersion := "2.11.6"

javacOptions in compile ++= Seq("-encoding", "UTF-8", "-source", "1.8", "-target", "1.8", "-Xlint")

javacOptions in doc ++= Seq("-encoding", "UTF-8", "-source", "1.8", "-Xdoclint:none")

testOptions += Tests.Argument(TestFrameworks.JUnit, "-v", "-a")

libraryDependencies ++= Seq(
  "com.typesafe.akka" %%      "akka-actor" % "2.4-M2",
  "com.typesafe.akka" %%    "akka-testkit" % "2.4-M2" % "test",
              "junit"  %           "junit" % "4.12"         % "test",
       "com.novocode"  % "junit-interface" % "0.11"         % "test")
