import sbt._
object PluginDef extends Build {
  override def projects = Seq(root)
  lazy val root = Project("plugins", file(".")) dependsOn(osgi)
  lazy val coveralls = uri("git://github.com/ezh/xsbt-coveralls-plugin.git#465a58d42f6617cb2383964d0388b4852b07dc66")
  lazy val osgi = uri("git://github.com/digimead/sbt-osgi-manager.git#0.0.1.2")
  lazy val scct = uri("git://github.com/ezh/sbt-scct.git#dbda90348a260995dea29eee97d03457742a8a67")
}
