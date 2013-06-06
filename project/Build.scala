import sbt._

object SBTGatlingBuild extends Build {
	lazy val root = Project(
		id = "sbt-gatling",
		base = file(".")
	) aggregate(support)

	lazy val support = Project(id = "support", base = file("support"))
}
