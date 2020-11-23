package ch.epfl.bluebrain.nexus.delta.rdf.jsonld

import ch.epfl.bluebrain.nexus.delta.rdf.Fixtures
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.BNode
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.UnexpectedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schema
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import io.circe.JsonObject
import io.circe.syntax._
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class CompactedJsonLdSpec extends AnyWordSpecLike with Matchers with Fixtures with Inspectors {

  "A compacted Json-LD" should {
    val expanded              = jsonContentOf("expanded.json")
    val context               = jsonContentOf("context.json").topContextValueOrEmpty
    val expectedCompacted     = jsonContentOf("compacted.json")
    val expandedNoId          = expanded.removeAll(keywords.id -> iri)
    val expectedCompactedNoId = expectedCompacted.removeAll("id" -> "john-doé")
    val rootBNode             = BNode.random

    "be constructed successfully" in {
      val compacted = JsonLd.compact(expanded, context, iri).accepted
      compacted.json.removeKeys(keywords.context) shouldEqual expectedCompacted.removeKeys(keywords.context)
      compacted.ctx shouldEqual context
      compacted.rootId shouldEqual iri
    }

    "be constructed successfully with a root blank node" in {
      val compacted = JsonLd.compact(expandedNoId, context, rootBNode).accepted
      compacted.json.removeKeys(keywords.context) shouldEqual expectedCompactedNoId.removeKeys(keywords.context)
      compacted.rootId shouldEqual rootBNode
    }

    "fail to find the root Iri from a multi-root json" in {
      val input = jsonContentOf("/jsonld/compacted/input-multiple-roots.json")
      JsonLd.compact(input, context, iri).rejectedWith[UnexpectedJsonLd]
    }

    "be constructed successfully from a multi-root json when using framing" in {
      val input     = jsonContentOf("/jsonld/compacted/input-multiple-roots.json")
      val compacted = JsonLd.frame(input, context, iri).accepted
      compacted.json.removeKeys(keywords.context) shouldEqual json"""{"id": "john-doé", "@type": "Person"}"""
    }

    "add literals" in {
      val compacted =
        CompactedJsonLd(JsonObject("@id" -> iri.asJson), context, iri)
      val result    = compacted.add("tags", "first").add("tags", 2).add("tags", 30L).add("tags", false)
      result.json.removeKeys(keywords.context) shouldEqual json"""{"@id": "$iri", "tags": [ "first", 2, 30, false ]}"""
    }

    "add @type Iri to existing @type" in {
      val (person, hero) = (schema.Person, schema + "Hero")
      val obj            = json"""{"@id": "$iri", "@type": "$person"}""".asObject.value
      val compacted      = CompactedJsonLd(obj, context, iri)
      val result         = compacted.addType(hero)
      result.json.removeKeys(keywords.context) shouldEqual json"""{"@id": "$iri", "@type": ["$person", "$hero"]}"""
    }

    "add @type Iri" in {
      val obj       = json"""{"@id": "$iri"}""".asObject.value
      val compacted = CompactedJsonLd(obj, context, iri)
      val result    = compacted.addType(schema.Person)
      result.json.removeKeys(keywords.context) shouldEqual json"""{"@id": "$iri", "@type": "${schema.Person}"}"""
    }

    "be converted to expanded form" in {
      val compacted = JsonLd.compact(expanded, context, iri).accepted
      compacted.toExpanded.accepted shouldEqual JsonLd.expandedUnsafe(expanded, iri)
    }

    "be converted to expanded form with a root blank node" in {
      val compacted = JsonLd.compact(expandedNoId, context, rootBNode).accepted
      compacted.toExpanded.accepted shouldEqual JsonLd.expandedUnsafe(expandedNoId, rootBNode)
    }

    "be converted to graph" in {
      val compacted = JsonLd.compact(expanded, context, iri).accepted
      val graph     = compacted.toGraph.accepted
      val expected  = contentOf("ntriples.nt", "bnode" -> bNode(graph).rdfFormat, "rootNode" -> iri.rdfFormat)
      graph.rootNode shouldEqual iri
      graph.toNTriples.rightValue.toString should equalLinesUnordered(expected)
    }

    "be converted to graph with a root blank node" in {
      val compacted = JsonLd.compact(expandedNoId, context, rootBNode).accepted
      val graph     = compacted.toGraph.accepted
      val expected  = contentOf("ntriples.nt", "bnode" -> bNode(graph).rdfFormat, "rootNode" -> rootBNode.rdfFormat)
      graph.rootNode shouldEqual rootBNode
      graph.toNTriples.rightValue.toString should equalLinesUnordered(expected)
    }
  }
}