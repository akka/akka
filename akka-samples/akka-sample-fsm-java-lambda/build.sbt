name := "akka-docs-java-lambda"

version := "2.4.0-RC3"

scalaVersion := "2.11.7"

javacOptions in compile ++= Seq("-encoding", "UTF-8", "-source", "1.8", "-target", "1.8", "-Xlint")

javacOptions in doc ++= Seq("-encoding", "UTF-8", "-source", "1.8")

testOptions += Tests.Argument(TestFrameworks.JUnit, "-v", "-a")

libraryDependencies ++= Seq(
  "com.typesafe.akka" %%      "akka-actor" % "2.4.0-RC3",
  "com.typesafe.akka" %%    "akka-testkit" % "2.4.0-RC3" % "test",
              "junit"  %           "junit" % "4.11"         % "test",
       "com.novocode"  % "junit-interface" % "0.10"         % "test")
