pgpKeyRing := Some(baseDirectory.value / "pubring.pgp")
scalaVersion := "2.13.2"
name := "test"
organization := "test"
version := "1.0"

TaskKey[Unit]("check") := {
  val sbtV = sbtBinaryVersion.value
  if (sbtV == "1.0") {
    val x = target.value / "scala-2.13" / "test_2.13-1.0.jar.asc"
    assert(x.exists())
  } else {
    import xsbti.VirtualFileRef
    val conv = fileConverter.value
    val p = conv.toPath(VirtualFileRef.of("${OUT}/jvm/scala-2.13.2/test/test_2.13-1.0.jar.asc"))
    assert(p.toFile.exists())
  }
}
