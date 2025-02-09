package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphView.{AggregateBlazegraphView, IndexingBlazegraphView}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection.ViewNotFound
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schema => schemaorg}
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AbstractDBSpec, ConfigFixtures}
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import monix.execution.Scheduler
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers

import java.util.UUID

class BlazegraphScopeInitializationSpec
    extends AbstractDBSpec
    with Matchers
    with Inspectors
    with IOFixedClock
    with IOValues
    with TestHelpers
    with ConfigFixtures
    with Fixtures {

  private val uuid                   = UUID.randomUUID()
  implicit private val uuidF: UUIDF  = UUIDF.fixed(uuid)
  implicit private val sc: Scheduler = Scheduler.global

  private val saRealm: Label              = Label.unsafe("service-accounts")
  private val usersRealm: Label           = Label.unsafe("users")
  implicit private val sa: ServiceAccount = ServiceAccount(User("nexus-sa", saRealm))
  implicit private val bob: Subject       = User("bob", usersRealm)

  private val org               = Label.unsafe("org")
  private val am                = ApiMappings("nxv" -> nxv.base, "Person" -> schemaorg.Person)
  private val projBase          = nxv.base
  private val project           =
    ProjectGen.project("org", "project", uuid = uuid, orgUuid = uuid, base = projBase, mappings = am)
  implicit val baseUri: BaseUri = BaseUri.withoutPrefix("http://localhost")

  val views: BlazegraphViews = BlazegraphViewsSetup.init(org, project, permissions.query)

  "A BlazegraphScopeInitialization" should {
    val init = new BlazegraphScopeInitialization(views, sa)

    "create a default SparqlView on newly created project" in {
      views.fetch(defaultViewId, project.ref).rejectedWith[ViewNotFound]
      init.onProjectCreation(project, bob).accepted
      val resource = views.fetch(defaultViewId, project.ref).accepted
      resource.value match {
        case v: IndexingBlazegraphView  =>
          v.resourceSchemas shouldBe empty
          v.resourceTypes shouldBe empty
          v.resourceTag shouldEqual None
          v.includeDeprecated shouldEqual true
          v.includeMetadata shouldEqual true
          v.permission shouldEqual permissions.query
        case _: AggregateBlazegraphView => fail("Expected an IndexingBlazegraphView to be created")
      }
      resource.rev shouldEqual 1L
      resource.createdBy shouldEqual sa.caller.subject
    }

    "not create a default SparqlView if one already exists" in {
      views.fetch(defaultViewId, project.ref).accepted.rev shouldEqual 1L
      init.onProjectCreation(project, bob).accepted
      views.fetch(defaultViewId, project.ref).accepted.rev shouldEqual 1L
    }
  }
}
