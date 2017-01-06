import akka._
import java.nio.file.Files
import java.nio.file.attribute.{ PosixFileAttributeView, PosixFilePermission }
import Dependencies.{ h2specName, h2specExe }

Dependencies.http2
Dependencies.http2Support

fork in run in Test := true
fork in Test := true

connectInput in run in Test := true

lazy val h2specPath = Def.task {
  (target in Test).value / h2specName / h2specExe
}

javaOptions in Test += "-Dh2spec.path=" + h2specPath.value
resourceGenerators in Test += Def.task {
  val h2spec = h2specPath.value

  if (!h2spec.exists) {
    streams.value.log.info("Extracting h2spec to " + h2spec)

    for (zip <- (update in Test).value.select(artifact = artifactFilter(name = h2specName, extension = "zip")))
      IO.unzip(zip, (target in Test).value)

    // Set the executable bit on the expected path to fail if it doesn't exist
    for (view <- Option(Files.getFileAttributeView(h2spec.toPath, classOf[PosixFileAttributeView]))) {
      val permissions = view.readAttributes.permissions
      if (permissions.add(PosixFilePermission.OWNER_EXECUTE))
        view.setPermissions(permissions)
    }
  }
  Seq(h2spec)
}.taskValue
