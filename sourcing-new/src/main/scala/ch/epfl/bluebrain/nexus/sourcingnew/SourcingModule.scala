package ch.epfl.bluebrain.nexus.sourcingnew

import akka.actor.typed.ActorSystem
import akka.stream.alpakka.cassandra.scaladsl.CassandraSession
import cats.effect.{Async, ContextShift}
import ch.epfl.bluebrain.nexus.sourcingnew.EventLog.{CassandraEventLogFactory, JdbcEventLogFactory}
import ch.epfl.bluebrain.nexus.sourcingnew.projections.cassandra.{CassandraConfig, CassandraProjection, CassandraSchemaMigration, ProjectionConfig}
import ch.epfl.bluebrain.nexus.sourcingnew.projections.jdbc.{JdbcConfig, JdbcProjection, JdbcSchemaMigration}
import ch.epfl.bluebrain.nexus.sourcingnew.projections.{Projection, SchemaMigration}
import distage.{Axis, ModuleDef, Tag, TagK}
import doobie.util.transactor.Transactor
import io.circe.{Decoder, Encoder}

object Persistence extends Axis {
  case object Cassandra extends AxisValueDef
  case object Postgres extends AxisValueDef
}

final class EventLogModule[F[_]: ContextShift: Async: TagK] extends ModuleDef {
  make[CassandraEventLogFactory[F]].tagged(Persistence.Cassandra)

  make[JdbcEventLogFactory[F]].tagged(Persistence.Postgres)
}

final class ProjectionModule[F[_]: ContextShift: Async: TagK, A: Encoder: Decoder: Tag] extends ModuleDef {

  // Cassandra
  make[SchemaMigration[F]].tagged(Persistence.Cassandra).from {
    (cassandraSession: CassandraSession, schemaConfig: CassandraConfig, actorSystem: ActorSystem[Nothing]) =>
      new CassandraSchemaMigration[F](cassandraSession, schemaConfig, actorSystem)

  }
  make[Projection[F, A]].tagged(Persistence.Cassandra).from {
    (cassandraSession: CassandraSession, projectionConfig: ProjectionConfig, actorSystem: ActorSystem[Nothing]) =>
      new CassandraProjection[F, A](cassandraSession, projectionConfig, actorSystem)

  }

  // Postgresql
  make[Transactor[F]].tagged(Persistence.Postgres).from {
    (jdbcConfig: JdbcConfig) =>
      Transactor.fromDriverManager[F](
        jdbcConfig.driver,
        jdbcConfig.url,
        jdbcConfig.username,
        jdbcConfig.password,
      )
  }
  make[SchemaMigration[F]].tagged(Persistence.Postgres).from {
    (jdbcConfig: JdbcConfig) =>
      new JdbcSchemaMigration[F](jdbcConfig)
  }
  make[Projection[F,A]].tagged(Persistence.Postgres).from {
    (xa: Transactor[F]) =>
      new JdbcProjection[F, A](xa)
  }

}

object ProjectionModule {
  final def apply[F[_]: ContextShift: Async: TagK, A: Encoder: Decoder: Tag]: ProjectionModule[F, A] =
    new ProjectionModule[F, A]
}