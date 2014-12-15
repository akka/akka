name := "akka-sample-fsm-java-lambda"

version := "15v01p01"

javacOptions ++= Seq("-source", "1.8", "-target", "1.8", "-Xlint")

testOptions += Tests.Argument(TestFrameworks.JUnit, "-v", "-a")

libraryDependencies ++= Seq(
  TypesafeLibrary.akkaActor.value,
  TypesafeLibrary.akkaTestkit.value % "test")

libraryDependencies ++= Seq(
         "junit"  %           "junit" % "4.11"         % "test",
  "com.novocode"  % "junit-interface" % "0.10"         % "test")
