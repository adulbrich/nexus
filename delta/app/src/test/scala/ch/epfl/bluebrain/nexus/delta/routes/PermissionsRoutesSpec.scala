package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.MediaRanges.`*/*`
import akka.http.scaladsl.model.MediaTypes.`text/event-stream`
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{`Last-Event-ID`, Accept}
import akka.http.scaladsl.server.{RejectionHandler, Route}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.marshalling.RdfMediaTypes._
import ch.epfl.bluebrain.nexus.delta.routes.marshalling.RdfRejectionHandler
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.{acls, orgs, realms}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{IdentitiesDummy, PermissionsDummy, RemoteContextResolutionDummy}
import ch.epfl.bluebrain.nexus.delta.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.testkit._
import monix.execution.Scheduler
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.Await
import scala.concurrent.duration._

class PermissionsRoutesSpec
    extends AnyWordSpecLike
    with ScalatestRouteTest
    with Matchers
    with CirceLiteral
    with DeltaDirectives
    with IOFixedClock
    with IOValues
    with RouteHelpers
    with TestMatchers
    with Inspectors
    with TestHelpers {

  implicit private val rcr: RemoteContextResolutionDummy =
    RemoteContextResolutionDummy(
      contexts.resource    -> jsonContentOf("contexts/resource.json"),
      contexts.error       -> jsonContentOf("contexts/error.json"),
      contexts.permissions -> jsonContentOf("contexts/permissions.json")
    )

  implicit private val ordering: JsonKeyOrdering          = JsonKeyOrdering(
    List("@context", "@id", "@type", "reason", "details"),
    List("_rev", "_deprecated", "_createdAt", "_createdBy", "_updatedAt", "_updatedBy", "_constrainedBy")
  )
  implicit private val s: Scheduler                       = Scheduler.global
  implicit private val baseUri: BaseUri                   = BaseUri("http://localhost:8080", Label.unsafe("v1"))
  implicit private val caller: Subject                    = Identity.Anonymous
  implicit private val rejectionHandler: RejectionHandler = RdfRejectionHandler.apply

  private val minimum     = Set(acls.read, acls.write)
  private val identities  = IdentitiesDummy(Map.empty)
  private val permissions = PermissionsDummy(minimum).accepted
  private val route       = Route.seal(PermissionsRoutes(identities, permissions))

  "The permissions routes" should {

    "fetch permissions" in {
      val expected = jsonContentOf(
        "permissions/fetch_compacted.jsonld",
        Map("rev" -> "0", "permissions" -> s""""${acls.read}","${acls.write}"""")
      )
      Get("/v1/permissions") ~> Accept(`*/*`) ~> route ~> check {
        response.asJson shouldEqual expected
        response.status shouldEqual StatusCodes.OK
        response.entity.contentType shouldEqual `application/ld+json`.toContentType
      }
    }

    "fetch permissions at specific revision" in {
      permissions.append(Set(realms.read), 0L).accepted

      val expected = jsonContentOf(
        "permissions/fetch_compacted.jsonld",
        Map("rev" -> "1", "permissions" -> s""""${acls.read}","${acls.write}","${realms.read}"""")
      )
      Get("/v1/permissions?rev=1") ~> Accept(`*/*`) ~> route ~> check {
        response.asJson shouldEqual expected
        response.status shouldEqual StatusCodes.OK
        response.entity.contentType shouldEqual `application/ld+json`.toContentType
      }
    }

    "replace permissions" in {
      val expected = jsonContentOf("permissions/resource.jsonld", Map("rev" -> "2"))

      val replace = json"""{"permissions": ["${realms.write}"]}"""
      Put("/v1/permissions?rev=1", replace.toEntity) ~> Accept(`*/*`) ~> route ~> check {
        response.asJson shouldEqual expected
        response.status shouldEqual StatusCodes.OK
        response.entity.contentType shouldEqual `application/ld+json`.toContentType
      }
      permissions.fetchAt(2L).accepted.value.permissions shouldEqual minimum + realms.write
    }

    "append permissions" in {
      val expected = jsonContentOf("permissions/resource.jsonld", Map("rev" -> "3"))

      val append = json"""{"@type": "Append", "permissions": ["${realms.read}", "${orgs.read}"]}"""
      Patch("/v1/permissions?rev=2", append.toEntity) ~> Accept(`*/*`) ~> route ~> check {
        response.asJson shouldEqual expected
        response.status shouldEqual StatusCodes.OK
        response.entity.contentType shouldEqual `application/ld+json`.toContentType
      }
      permissions.fetchAt(3L).accepted.value.permissions shouldEqual
        minimum ++ Set(realms.write, realms.read, orgs.read)
    }

    "subtract permissions" in {
      val expected = jsonContentOf("permissions/resource.jsonld", Map("rev" -> "4"))

      val subtract = json"""{"@type": "Subtract", "permissions": ["${realms.read}", "${realms.write}"]}"""
      Patch("/v1/permissions?rev=3", subtract.toEntity) ~> Accept(`*/*`) ~> route ~> check {
        response.asJson shouldEqual expected
        response.status shouldEqual StatusCodes.OK
        response.entity.contentType shouldEqual `application/ld+json`.toContentType
      }
      permissions.fetchAt(4L).accepted.value.permissions shouldEqual minimum + orgs.read
    }

    "delete permissions" in {
      val expected = jsonContentOf("permissions/resource.jsonld", Map("rev" -> "5"))

      Delete("/v1/permissions?rev=4") ~> Accept(`*/*`) ~> route ~> check {
        response.asJson shouldEqual expected
        response.status shouldEqual StatusCodes.OK
        response.entity.contentType shouldEqual `application/ld+json`.toContentType
      }
      permissions.fetchAt(5L).accepted.value.permissions shouldEqual minimum
    }

    "reject on PATCH request" in {
      val wrongPatch = json"""{"@type": "Other", "permissions": ["${realms.read}"]}"""
      val err        = s"Expected value 'Replace' or 'Subtract' when using 'PATCH'."

      Patch("/v1/permissions?rev=5", wrongPatch.toEntity) ~> Accept(`*/*`) ~> route ~> check {
        response.asJson shouldEqual jsonContentOf("permissions/reject_malformed.jsonld", Map("msg" -> err))
        response.status shouldEqual StatusCodes.BadRequest
        response.entity.contentType shouldEqual `application/ld+json`.toContentType
      }
    }

    "reject on PUT request" in {
      val wrongReplace = json"""{"@type": "Other", "permissions": ["${realms.write}"]}"""
      val err          = s"Expected value 'Replace' when using 'PUT'."

      Put("/v1/permissions?rev=5", wrongReplace.toEntity) ~> Accept(`*/*`) ~> route ~> check {
        response.asJson shouldEqual jsonContentOf("permissions/reject_malformed.jsonld", Map("msg" -> err))
        response.status shouldEqual StatusCodes.BadRequest
        response.entity.contentType shouldEqual `application/ld+json`.toContentType
      }
    }

    "reject on wrong revision query param" in {
      val replace = json"""{"permissions": ["${realms.write}"]}"""
      Put("/v1/permissions?rev=6", replace.toEntity) ~> Accept(`*/*`) ~> route ~> check {
        response.asJson shouldEqual jsonContentOf(
          "permissions/reject_incorrect_rev.jsonld",
          Map("provided" -> "6", "expected" -> "5")
        )
        response.status shouldEqual StatusCodes.Conflict
        response.entity.contentType shouldEqual `application/ld+json`.toContentType
      }
    }

    "reject on non existing resource endpoint" in {
      Get("/v1/other") ~> Accept(`*/*`) ~> route ~> check {
        response.asJson shouldEqual jsonContentOf("permissions/reject_endpoint_not_found.jsonld")
        response.status shouldEqual StatusCodes.NotFound
        response.entity.contentType shouldEqual `application/ld+json`.toContentType
      }
    }

    "return the event stream when no offset is provided" in {
      val dummy = PermissionsDummy(Set.empty, 5L).accepted
      val route = Route.seal(PermissionsRoutes(identities, dummy))
      dummy.append(Set(acls.read), 0L).accepted
      dummy.subtract(Set(acls.read), 1L).accepted
      dummy.replace(Set(acls.write), 2L).accepted
      dummy.delete(3L).accepted
      dummy.append(Set(acls.read), 4L).accepted
      dummy.append(Set(realms.write), 5L).accepted
      dummy.subtract(Set(realms.write), 6L).accepted
      Get("/v1/permissions/events") ~> Accept(`*/*`) ~> route ~> check {
        mediaType shouldBe `text/event-stream`
        val value    = Await.result(responseEntity.dataBytes.runFold(ByteString(""))(_ ++ _).map(_.utf8String), 3.seconds)
        val expected = contentOf("/permissions/eventstream-0-5.txt")
        value shouldEqual expected
      }
    }

    "return the event stream when an offset is provided" in {
      val dummy = PermissionsDummy(Set.empty, 5L).accepted
      val route = Route.seal(PermissionsRoutes(identities, dummy))
      dummy.append(Set(acls.read), 0L).accepted
      dummy.subtract(Set(acls.read), 1L).accepted
      dummy.replace(Set(acls.write), 2L).accepted
      dummy.delete(3L).accepted
      dummy.append(Set(acls.read), 4L).accepted
      dummy.append(Set(realms.write), 5L).accepted
      dummy.subtract(Set(realms.write), 6L).accepted
      Get("/v1/permissions/events") ~> Accept(`*/*`) ~> `Last-Event-ID`("2") ~> route ~> check {
        mediaType shouldBe `text/event-stream`
        val value    = Await.result(responseEntity.dataBytes.runFold(ByteString(""))(_ ++ _).map(_.utf8String), 3.seconds)
        val expected = contentOf("/permissions/eventstream-2-7.txt")
        value shouldEqual expected
      }
    }
  }

}