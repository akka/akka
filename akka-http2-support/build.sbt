import akka._
import java.nio.file.Files
import java.nio.file.attribute.{ PosixFileAttributeView, PosixFilePermission }

Dependencies.http2
Dependencies.http2Support

fork in run in Test := true
fork in Test := true

connectInput in run in Test := true

javaOptions in Test += "-Dh2spec.path=" + ((target in Test).value / Dependencies.h2SpecPackageName / s"h2spec${exeIfWindows}")

def exeIfWindows = {
  val os = System.getProperty("os.name").toLowerCase()
  if (os startsWith "win") ".exe"
  else ""
}

resourceGenerators in Test += Def.task {
  val h2SpecOut = (target in Test).value
  streams.value.log.info("Extracting h2spec binary")

  for {
    zip <- (update in Test).value.select(artifact = artifactFilter(name = "h2spec", extension = "zip")).take(1)
    unzipped <- IO.unzip(zip, (target in Test).value)
  } yield {
    Option(Files.getFileAttributeView(unzipped.toPath, classOf[PosixFileAttributeView])) foreach { view =>
      val permissions = view.readAttributes.permissions
      permissions.add(PosixFilePermission.OWNER_EXECUTE)
      view.setPermissions(permissions)
    }
    unzipped
  }
}.taskValue
