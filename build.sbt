sbtPlugin := true

name := "sbt-gatling-plugin"

libraryDependencies ++= Seq(
	"com.excilys.ebi.gatling.highcharts" % "gatling-charts-highcharts" % "1.5.1"
)

if (System.getProperty("rbh.build.id") == null) 
resolvers ++= Seq(
	"excilys" at "https://repository.excilys.com/content/groups/public/",
	"sonatype-releases" at "https://oss.sonatype.org/content/repositories/releases"
) else 
Seq.empty

organization := "com.redbrickhealth"

