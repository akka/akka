name := "akka-docs-java-lambda"

version := "2.3-SNAPSHOT"

scalaVersion := "2.10.5"

compileOrder := CompileOrder.ScalaThenJava

javacOptions ++= Seq("-source", "1.8", "-target", "1.8", "-Xlint")

testOptions += Tests.Argument(TestFrameworks.JUnit, "-v", "-a")

val publishedAkkaVersion = "2.3.11"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %%      "akka-actor"                       % publishedAkkaVersion,
  "com.typesafe.akka" %%      "akka-testkit"                     % publishedAkkaVersion % "test",
  "com.typesafe.akka" %%      "akka-stream-experimental"         % "1.0-SNAPSHOT",
  "com.typesafe.akka" %%      "akka-stream-testkit-experimental" % "1.0-SNAPSHOT" % "test",
              "junit"  %      "junit"                            % "4.11"         % "test",
       "com.novocode"  %      "junit-interface"                  % "0.10"         % "test")
