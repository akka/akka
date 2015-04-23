name := "akka-docs-java-lambda"

version := "2.3.10"

scalaVersion := "2.10.4"

compileOrder := CompileOrder.ScalaThenJava

javacOptions ++= Seq("-source", "1.8", "-target", "1.8", "-Xlint")

testOptions += Tests.Argument(TestFrameworks.JUnit, "-v", "-a")

libraryDependencies ++= Seq(
  "com.typesafe.akka" %%      "akka-actor" % "2.3.10",
  "com.typesafe.akka" %%    "akka-testkit" % "2.3.10" % "test",
              "junit"  %           "junit" % "4.11"         % "test",
       "com.novocode"  % "junit-interface" % "0.10"         % "test")
