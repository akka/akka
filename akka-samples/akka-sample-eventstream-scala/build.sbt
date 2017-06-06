val akkaVersion = "2.3.12"

val project = Project(
  id = "akka-sample-eventstream-scala",
  base = file("."),
  settings = Project.defaultSettings ++
    Seq(
      name := """akka-sample-eventstream-scala""",
      scalaVersion := "2.11.6",
      scalacOptions in Compile ++= Seq("-encoding", "UTF-8", "-target:jvm-1.8", "-deprecation", "-feature", "-unchecked", "-Xlog-reflective-calls", "-Xlint"),
      javaOptions in run ++= Seq("-Xms128m", "-Xmx1024m"),
      libraryDependencies ++= Seq("com.typesafe.akka" %% "akka-actor" % "2.3.12")
    )
)

