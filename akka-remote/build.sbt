import akka.{AkkaBuild, Dependencies, Formatting, Protobuf, OSGi}

AkkaBuild.defaultSettings
Formatting.formatSettings
OSGi.remote
Dependencies.remote
Protobuf.settings

parallelExecution in Test := false
