import sbt.Keys.scalaVersion
import sbt.Keys.version

val scalaVersions = Seq("2.12.9", "2.11.12")

lazy val scalaSettings = Seq(
  crossScalaVersions := scalaVersions,
  scalaVersion := scalaVersions.head,
  scalacOptions ++= Seq(
    "-encoding",
    "utf8",
    "-deprecation",
    "-feature",
    "-unchecked"
  )
)

lazy val publishSettings = Seq(
  homepage := Some(url("https://github.com/mliarakos/lagom-broker-sqs")),
  licenses := Seq(("Apache-2.0", url("https://www.apache.org/licenses/LICENSE-2.0"))),
  organizationHomepage := Some(url("https://github.com/mliarakos")),
  pomExtra := {
    <developers>
      <developer>
        <id>mliarakos</id>
        <name>Michael Liarakos</name>
        <url>https://github.com/mliarakos</url>
      </developer>
    </developers>
  },
  pomIncludeRepository := { _ =>
    false
  },
  publishMavenStyle := true,
  publishTo := {
    val nexus = "https://oss.sonatype.org"
    if (isSnapshot.value) Some("snapshots".at(s"$nexus/content/repositories/snapshots"))
    else Some("releases".at(s"$nexus/service/local/staging/deploy/maven2"))
  },
  scmInfo := Some(
    ScmInfo(
      url("https://github.com/mliarakos/lagom-broker-sqs"),
      "scm:git:git@github.com:mliarakos/lagom-broker-sqs.git"
    )
  )
)

lazy val commonSettings = scalaSettings ++ publishSettings ++ Seq(
  organization := "com.github.mliarakos",
  version := s"0.1.0"
)

val lagomVersion = "1.5.3"

val alpakkaSqs           = "com.lightbend.akka"  %% "akka-stream-alpakka-sqs" % "1.1.1"
val lagomApi             = "com.lightbend.lagom" %% "lagom-api"               % lagomVersion
val lagomApiScalaDsl     = "com.lightbend.lagom" %% "lagom-scaladsl-api"      % lagomVersion
val lagomPersistenceCore = "com.lightbend.lagom" %% "lagom-persistence-core"  % lagomVersion
val lagomScaladslBroker  = "com.lightbend.lagom" %% "lagom-scaladsl-broker"   % lagomVersion
val lagomScaladslServer  = "com.lightbend.lagom" %% "lagom-scaladsl-server"   % lagomVersion
val elasticmqSqs         = "org.elasticmq"       %% "elasticmq-rest-sqs"      % "0.14.12" % Test
val scalaTest            = "org.scalatest"       %% "scalatest"               % "3.0.5" % Test

lazy val `sqs-client` = project
  .in(file("service/core/sqs/client"))
  .settings(commonSettings: _*)
  .settings(
    name := "lagom-sqs-client",
    libraryDependencies ++= Seq(
      alpakkaSqs,
      lagomApi
    )
  )

lazy val `sqs-client-scaladsl` = project
  .in(file("service/scaladsl/sqs/client"))
  .settings(commonSettings: _*)
  .settings(
    name := "lagom-scaladsl-sqs-client",
    libraryDependencies ++= Seq(
      lagomApiScalaDsl,
      scalaTest
    )
  )
  .dependsOn(`sqs-client`)

lazy val `sqs-broker` = project
  .in(file("service/core/sqs/server"))
  .settings(commonSettings: _*)
  .settings(
    name := "lagom-sqs-broker",
    libraryDependencies ++= Seq(
      lagomApi,
      lagomPersistenceCore,
      scalaTest
    )
  )
  .dependsOn(`sqs-client`)

lazy val `sqs-broker-scaladsl` = project
  .in(file("service/scaladsl/sqs/server"))
  .settings(commonSettings: _*)
  .settings(
    name := "lagom-scaladsl-sqs-broker",
    libraryDependencies ++= Seq(
      lagomScaladslBroker,
      lagomScaladslServer,
      elasticmqSqs,
      scalaTest
    )
  )
  .dependsOn(`sqs-broker`, `sqs-client-scaladsl`)

lazy val `lagom-broker-sqs` = project
  .in(file("."))
  .settings(commonSettings: _*)
  .settings(
    publish / skip := true,
    publishLocal / skip := true
  )
  .aggregate(
    `sqs-client`,
    `sqs-client-scaladsl`,
    `sqs-broker`,
    `sqs-broker-scaladsl`
  )
