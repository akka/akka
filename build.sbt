disablePlugins(MimaPlugin)

lazy val root = Project(
  id = "akka",
  base = file(".")
).aggregate(actor)

lazy val actor = Project(id = "akka-actor", base = file("akka-actor "))
  .settings(akka.AkkaBuild.buildSettings)

