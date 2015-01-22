name := "akka-docs-java-lambda"

version := "2.4-SNAPSHOT"

scalaVersion := "2.11.5"

javacOptions ++= Seq("-source", "1.8", "-target", "1.8", "-Xlint")

testOptions += Tests.Argument(TestFrameworks.JUnit, "-v", "-a")

libraryDependencies ++= Seq(
  "com.typesafe.akka" %%      "akka-actor" % "2.4-SNAPSHOT",
  "com.typesafe.akka" %%    "akka-testkit" % "2.4-SNAPSHOT" % "test",
              "junit"  %           "junit" % "4.11"         % "test",
       "com.novocode"  % "junit-interface" % "0.10"         % "test")
