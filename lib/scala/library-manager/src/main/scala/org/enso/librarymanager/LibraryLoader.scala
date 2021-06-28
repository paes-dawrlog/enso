package org.enso.librarymanager

import org.enso.cli.task.ProgressReporter
import org.enso.distribution.DistributionManager
import org.enso.distribution.locking.{LockUserInterface, ResourceManager}
import org.enso.editions.{Editions, LibraryName}
import org.enso.librarymanager.local.LocalLibraryProvider
import org.enso.librarymanager.published.{
  LibraryDownloader,
  PublishedLibraryProvider
}
import org.enso.librarymanager.published.cache.LibraryCache

import java.nio.file.Path
import scala.util.{Failure, Try}

/** A helper class for loading libraries. */
case class LibraryLoader(
  distributionManager: DistributionManager,
  resourceManager: ResourceManager,
  progressReporter: ProgressReporter,
  lockReporter: LockUserInterface
) {
  private val localLibraryProvider =
    LocalLibraryProvider.make(distributionManager)
  private val resolver   = LibraryResolver(localLibraryProvider)
  private val downloader = new LibraryDownloader(progressReporter)
  private val publishedLibraryProvider: PublishedLibraryProvider =
    new LibraryCache(
      root              = distributionManager.paths.cachedLibraries,
      libraryDownloader = downloader,
      resourceManager   = resourceManager,
      lockReporter      = lockReporter
    )

  /** Resolves the library version that should be used based on the
    * configuration and returns its location on the filesystem.
    *
    * If the library is not available, this operation may download it.
    */
  def findLibrary(
    libraryName: LibraryName,
    edition: Editions.ResolvedEdition,
    preferLocalLibraries: Boolean
  ): Try[Path] = {
    val resolvedVersion = resolver
      .resolveLibraryVersion(libraryName, edition, preferLocalLibraries)
    resolvedVersion match {
      case Left(error) =>
        Failure(error)
      case Right(LibraryVersion.Local) =>
        localLibraryProvider
          .findLibrary(libraryName)
          .toRight {
            LibraryResolutionError(
              s"Edition configuration forces to use the local version, but " +
              s"the `$libraryName` library is not present among local " +
              s"libraries."
            )
          }
          .toTry
      case Right(LibraryVersion.Published(version, repository)) =>
        val dependencyResolver = { name: LibraryName =>
          resolver
            .resolveLibraryVersion(name, edition, preferLocalLibraries)
            .toOption
        }

        publishedLibraryProvider.findLibrary(
          libraryName,
          version,
          repository,
          dependencyResolver
        )
    }
  }
}
