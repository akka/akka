import akka._

AkkaBuild.defaultSettings
AkkaBuild.dontPublishSettings
Formatting.formatSettings
Dependencies.httpTests

// don't ignore Suites which is the default for the junit-interface
testOptions += Tests.Argument(TestFrameworks.JUnit, "--ignore-runners=")

scalacOptions in Compile  += "-language:_"
mainClass in run in Test := Some("akka.http.javadsl.SimpleServerApp")

disablePlugins(MimaPlugin)
