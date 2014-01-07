name := "sbt-gatling-support"

libraryDependencies ++= Seq(
	"org.scala-sbt" % "test-interface" % "1.0",
	"org.scala-sbt" % "sbt" % "0.13.1",
	"io.gatling" % "gatling-charts" % "2.0.0-M3"
)

organization := "com.redbrickhealth"
