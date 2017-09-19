import akka._

AkkaBuild.defaultSettings
Formatting.formatSettings
OSGi.stream
Dependencies.stream

val jdkVersion: String = System.getProperty("java.version")

unmanagedSourceDirectories in Compile ++= {
  if (jdkVersion startsWith "9") {
    println("[JDK9] Enabled [...-jdk9-lib] directories to be compiled; They will be compiled with 1.8 class format.")
    Seq(
      (sourceDirectory in Compile).value / "java-jdk9-lib",
      (sourceDirectory in Compile).value / "scala-jdk9-lib"
    )
  } else Seq.empty
}

unmanagedSourceDirectories in Test ++= {
  if (jdkVersion startsWith "9") {
    Seq(
      (sourceDirectory in Test).value / "java-jdk9-lib",
      (sourceDirectory in Test).value / "scala-jdk9-lib"
    )
  } else Seq.empty
}

enablePlugins(spray.boilerplate.BoilerplatePlugin)
