package com.jsuereth.sbtpgp

import sbt._
import Keys._
import sbt.sbtpgp.Compat._

/** Configuration class for an Ivy module that will pull PGP signatures. */
final case class GetSignaturesModule(id: ModuleID, modules: Seq[ModuleID], configurations: Seq[Configuration])

/** Configuration class for using Ivy to get PGP signatures. */
final case class GetSignaturesConfiguration(
    module: GetSignaturesModule,
    configuration: UpdateConfiguration,
    ivyScala: Option[IvyScala]
)

/** An enumeration for PGP signature verification results. */
sealed trait SignatureCheckResult
object SignatureCheckResult {

  /** The signature is ok and we trust it. */
  case object OK extends SignatureCheckResult

  /** The dependency has no PGP signature. */
  case object MISSING extends SignatureCheckResult

  /** The dependency is ok, but we don't trust the signer. */
  case class UNTRUSTED(key: Long) extends SignatureCheckResult {
    // TODO - Is the key really an integer value for output?  GPG only expects 8-character hex...
    override def toString = "UNTRUSTED(0x%x)" format (key.toInt)
  }

  /** The signature is all-out bad. */
  case object BAD extends SignatureCheckResult
}

/** The result of checking the signature of a given artifact in a module. */
case class SignatureCheck(module: ModuleID, artifact: Artifact, result: SignatureCheckResult) {
  override def toString =
    "%s:%s:%s:%s [%s]" format (module.organization, module.name, module.revision, artifact.`type`, result.toString)
}

/** A report of the PGP signature check results. */
case class SignatureCheckReport(results: Seq[SignatureCheck])

/** Helper utilties to check PGP signatures in SBT. */
object PgpSignatureCheck {

  /** Downloads PGP signatures so we can test them. */
  def resolveSignatures(ivySbt: IvySbt, config: GetSignaturesConfiguration, log: Logger): UpdateReport = {

    // lets us ignore configuration for the purposes of resolving signatures.
    def restrictedCopy(m: ModuleID, confs: Boolean) =
      subConfiguration(m, confs)

    // Converts a module to a module that includes signature artifacts explicitly.
    def signatureArtifacts(m: ModuleID): Option[ModuleID] = {
      // TODO - Some kind of filtering
      // TODO - We *can't* assume everything is a jar
      def signatureFor(artifact: Artifact) = Seq(artifact, artifact.withExtension(artifact.extension + gpgExtension))
      // Assume no explicit artifact = "jar" artifact.
      if (m.explicitArtifacts.isEmpty)
        Some(
          subExplicitArtifacts(m, Vector(Artifact(m.name, "jar", "jar"), Artifact(m.name, "jar", "jar" + gpgExtension)))
        )
      else Some(subExplicitArtifacts(m, m.explicitArtifacts.toVector flatMap signatureFor))
    }
    import config.{ configuration => c, module => mod, _ }
    import mod.{ configurations => confs, _ }

    val baseModules = modules map { m =>
      restrictedCopy(m, false)
    }
    val deps = (baseModules.distinct flatMap signatureArtifacts).toVector
    val base = restrictedCopy(id, true)
    val module = new ivySbt.Module(
      mkInlineConfiguration(base, deps, ivyScala, confs.toVector)
    )
    val upConf = c.withMissingOk(true)

    updateEither(module, upConf, UnresolvedWarningConfiguration(), LogicalClock.unknown, None, log) match {
      case Right(r) => r
      case Left(w)  => throw w.resolveException
    }
  }

  def mkInlineConfiguration(
      base: ModuleID,
      deps: Vector[ModuleID],
      ivyScala: Option[IvyScala],
      confs: Vector[Configuration]
  ): InlineConfiguration =
    ModuleDescriptorConfiguration(base, ModuleInfo(base.name))
      .withDependencies(deps)
      .withScalaModuleInfo(ivyScala)
      .withConfigurations(confs)

  def checkSignaturesTask(update: UpdateReport, pgp: PgpVerifierFactory, s: TaskStreams): SignatureCheckReport = {
    val report = SignatureCheckReport(checkArtifactSignatures(update, pgp, s) ++ missingSignatures(update, s))
    // TODO - Print results in different task, or provide a report as well.
    // TODO - Allow different log levels
    // TODO - Does sort-with for pretty print make any sense?
    prettyPrintSignatureReport(report, s)
    if (report.results exists (x => x.result != SignatureCheckResult.OK && x.result != SignatureCheckResult.MISSING))
      sys.error("Some artifacts have bad signatures or are signed by untrusted sources!")
    report
  }

  @deprecated(message = "use prettyPrintSignatureReport", since = "2.1.3")
  def prettyPrintSingatureReport(report: SignatureCheckReport, s: TaskStreams): Unit =
    prettyPrintSignatureReport(report, s)

  /** Pretty-prints a report to the logs of all the PGP signature results. */
  def prettyPrintSignatureReport(report: SignatureCheckReport, s: TaskStreams): Unit =
    if (report.results.isEmpty) s.log.info("----- No Dependencies for PGP check -----")
    else {
      import report._
      s.log.info("----- PGP Signature Results -----")
      val maxOrgWidth = (results.view map { case SignatureCheck(m, _, _)     => m.organization.size }).max
      val maxNameWidth = (results.view map { case SignatureCheck(m, _, _)    => m.name.size }).max
      val maxVersionWidth = (results.view map { case SignatureCheck(m, _, _) => m.revision.size }).max
      val maxTypeWidth = (results.view map { case SignatureCheck(_, a, _)    => a.`type`.size }).max
      val formatString = "  %" + maxOrgWidth + "s : %" + maxNameWidth + "s : %" + maxVersionWidth + "s : %" + maxTypeWidth + "s   [%s]"
      def prettify(s: SignatureCheck) =
        formatString format (s.module.organization, s.module.name, s.module.revision, s.artifact.`type`, s.result)
      results sortWith {
        case (a, b) if a.result == b.result                          => a.toString < b.toString
        case (SignatureCheck(_, _, SignatureCheckResult.OK), _)      => true
        case (_, SignatureCheck(_, _, SignatureCheckResult.OK))      => false
        case (SignatureCheck(_, _, SignatureCheckResult.MISSING), _) => true
        case (_, SignatureCheck(_, _, SignatureCheckResult.MISSING)) => false
        case (a, b)                                                  => a.toString < b.toString
      } foreach { x =>
        s.log.info(prettify(x))
      }
    }

  /** Returns the SignatureCheck results for all missing signature artifacts in an update. */
  private def missingSignatures(update: UpdateReport, s: TaskStreams): Seq[SignatureCheck] =
    for {
      config <- update.configurations
      module <- config.modules
      artifact <- module.missingArtifacts
      if artifact.extension endsWith gpgExtension
    } yield SignatureCheck(module.module, artifact, SignatureCheckResult.MISSING)

  /** Returns the SignatureCheck results for all downloaded signature artifacts. */
  private def checkArtifactSignatures(
      update: UpdateReport,
      pgp: PgpVerifierFactory,
      s: TaskStreams
  ): Seq[SignatureCheck] = {
    pgp.withVerifier(
      pgp =>
        for {
          config <- update.configurations
          module <- config.modules
          (artifact, file) <- module.artifacts
          if file.getName endsWith gpgExtension
        } yield SignatureCheck(module.module, artifact, pgp.verifySignature(file, s))
    )
  }
}
