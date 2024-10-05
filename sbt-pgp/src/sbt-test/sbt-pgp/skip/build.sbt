lazy val root = (project in file("."))
  .settings(
    GlobalScope / credentials := Seq(Credentials("", "pgp", "", "test password")),
    pgpSecretRing := baseDirectory.value / "secring.pgp",
    pgpPublicRing := baseDirectory.value / "pubring.pgp",
    scalaVersion := "2.13.2",
    name := "test",
    organization := "test",
    version := "1.0",
    publish / skip := true
  )
