package ch.epfl.bluebrain.nexus.sourcingnew

import akka.persistence.query.Offset
import cats.Applicative
import ch.epfl.bluebrain.nexus.sourcingnew.projections.{FailureMessage, Projection, ProjectionId, ProjectionProgress}

import scala.collection.mutable.Map
import fs2.Stream

/**
  * A In Memory projection for tests
  * @param F
  * @tparam F
  * @tparam A
  */
class InMemoryProjection[F[_], A](implicit F: Applicative[F])
  extends Projection[F, A] {

  private val progressMap = Map[ProjectionId, ProjectionProgress]()

  private val progressFailures = Map[ProjectionId, FailureMessage[A]]()

  /**
    * Records progress against a projection identifier.
    *
    * @param id       the projection identifier
    * @param progress the offset to record
    * @return a future () value
    */
  override def recordProgress(id: ProjectionId, progress: ProjectionProgress): F[Unit] =
    F.pure(progressMap += id -> progress)

  /**
    * Retrieves the progress for the specified projection projectionId. If there is no record of progress
    * the [[ProjectionProgress.NoProgress]] is returned.
    *
    * @param id an unique projectionId for a projection
    * @return a future progress value for the specified projection projectionId
    */
  override def progress(id: ProjectionId): F[ProjectionProgress] =
    F.pure(progressMap.getOrElse(id, ProjectionProgress.NoProgress))

  /**
    * Record a specific event against a index failures log projectionId.
    *
    * @param id             the project identifier
    * @param failureMessage the failure message to persist
    */
  override def recordFailure(id: ProjectionId, failureMessage: FailureMessage[A], f: Throwable => String): F[Unit] =
    F.pure(progressFailures += id -> failureMessage)

  /**
    * An event stream for all failures recorded for a projection.
    *
    * @param id the projection identifier
    * @return a source of the failed events
    */
  override def failures(id: ProjectionId): Stream[F, (A, Offset, String)] =
    Stream.emits(progressFailures.map {
      case (_, failure) => (failure.value, failure.offset, failure.throwable.getMessage)
    }.toSeq)
}