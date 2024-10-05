package sbt
package sbtpgp

import sbt.{ librarymanagement => lm }
import sbt.internal.{ librarymanagement => ilm }
import Keys._
import com.jsuereth.sbtpgp.PgpKeys._
import com.jsuereth.sbtpgp.gpgExtension

object Compat {
  val IvyActions = ilm.IvyActions
  type IvySbt = ilm.IvySbt
  type IvyScala = lm.ScalaModuleInfo
  type UpdateConfiguration = lm.UpdateConfiguration
  type UnresolvedWarning = lm.UnresolvedWarning
  type UnresolvedWarningConfiguration = lm.UnresolvedWarningConfiguration
  val UnresolvedWarningConfiguration = lm.UnresolvedWarningConfiguration

  val ivyScala = Keys.scalaModuleInfo

  def pgpRequires: Plugins = sbt.plugins.IvyPlugin

  def subConfiguration(m: ModuleID, confs: Boolean): ModuleID =
    m.withConfigurations(
      if (confs) m.configurations
      else None
    )

  def subExplicitArtifacts(m: ModuleID, artifacts: Vector[Artifact]): ModuleID =
    m.withExplicitArtifacts(artifacts)

  // This hack to access private[sbt]
  def updateEither(
      module: IvySbt#Module,
      configuration: UpdateConfiguration,
      uwconfig: UnresolvedWarningConfiguration,
      logicalClock: LogicalClock,
      depDir: Option[File],
      log: Logger
  ): Either[UnresolvedWarning, UpdateReport] =
    IvyActions.updateEither(module, configuration, uwconfig, log)

  val signedArtifacts = taskKey[Map[Artifact, xsbti.HashedVirtualFileRef]](
    "Packages all artifacts for publishing and maps the Artifact definition to the generated file."
  )

  def signingSettings0: Seq[Setting[_]] = Seq(
    signedArtifacts := {
      val artifacts = packagedArtifacts.value
      val r = pgpSigner.value
      val skipZ = (pgpSigner / skip).value
      val s = streams.value
      if (!skipZ) {
        val c = fileConverter.value
        artifacts.flatMap {
          case (art, file) =>
            val p = c.toPath(file)
            val signed = c.toVirtualFile(
              r.sign(p.toFile(), new File(p.toFile().getAbsolutePath + gpgExtension), s).toPath()
            )
            // r.sign(p.toFile(), new File(p.toFile().getAbsolutePath + gpgExtension)
            Seq(
              art -> file,
              art.withExtension(art.extension + gpgExtension) -> signed
            )
        }
      } else artifacts
    }
  )

  def toFile(vf: xsbti.HashedVirtualFileRef, c: xsbti.FileConverter): File =
    c.toPath(vf).toFile()
}
