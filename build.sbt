sbtPlugin := true

name := "sbt-gatling-plugin"

libraryDependencies ++= Seq(
	"io.gatling" % "gatling-charts" % "2.0.0-M3"
)

if (System.getProperty("rbh.build.id") == null) 
resolvers ++= Seq(
	"excilys" at "https://repository.excilys.com/content/groups/public/",
	"sonatype-releases" at "https://oss.sonatype.org/content/repositories/releases"
) else 
Seq.empty

organization := "com.redbrickhealth"

