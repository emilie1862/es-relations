import org.elasticsearch.action.index.IndexResponse
import org.elasticsearch.action.search.SearchResponse
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeAll
import org.slf4j.LoggerFactory
import kotlin.system.measureTimeMillis

val person1Name = "Emilie"
val person2Name = "Colin"

internal class ElasticDAOTest {

    companion object {
        val logger = LoggerFactory.getLogger(ElasticDAOTest::class.java)
        lateinit var elasticDAO: ElasticDAO

        //@Timeout(value = 10, unit = SECONDS)
        @BeforeAll
        @JvmStatic
        internal fun setup() {
            elasticDAO = ElasticDAO()

            val person1 = Person("person1", person1Name)
            person1.addRelationship("relatedPlace", mutableListOf(Place("place1", "Leesburg")))
            person1.addBinId("bin1")

            val event1 = Event("event1","Trying out ES")
            event1.addRelationship("relatedPlace", mutableListOf(Place("place1", "Leesburg")))
            event1.addRelationship("relatedPerson", mutableListOf(Person("person1", person1Name)))
            event1.addBinId("bin1")
            val event2 = Event("event2","Going to work")
            event2.addRelationship("relatedPerson", mutableListOf(Person("person2", person2Name), Person("person1", person1Name)))
            event2.addBinId("bin2")

//            val event3 = Event("event3", "Gathering of people")
//            ((1..10000)).forEach {
//                event3.addRelationship("relatedPerson", mutableListOf(Person("genPerson$it", "genPerson$it")))
//            }

            //If we add 10000 Person relationships, we get this error:
            //The number of nested documents has exceeded the allowed limit of [10000].
            // This limit can be set by changing the [index.mapping.nested_objects.limit] index level setting.

            logger.info("=======Indexing=======")
            indexWithMetrics(event1)
            indexWithMetrics(person1)
            indexWithMetrics(event2)
//            indexWithMetrics(event3)
            logger.info("=======Searching=======")
        }

        private fun indexWithMetrics(knote : Knote) : IndexResponse {
            val start = System.currentTimeMillis()
            val response = elasticDAO.index(knote)
            val took = System.currentTimeMillis() - start
            logger.info("Indexing ${knote.id} took ${took}ms")
            return response
        }
    }

    private fun searchWithMetrics(searchParams: Map<String, List<String>>) : SearchResponse {
        val start = System.currentTimeMillis()
        val response = elasticDAO.search(searchParams)
        val took = System.currentTimeMillis() - start
        logger.info("Filtering by $searchParams found ${response.hits.totalHits.value} results and took Elasticsearch ${response.took}")
        logger.info("Round trip search took ${took}ms")
        return response
    }

    @Test
    fun `Match all kNotes`() {
        val searchParams = HashMap<String, List<String>>()
        val response = searchWithMetrics(searchParams)

        assertEquals(5, response.hits.totalHits.value)
    }

    @Test
    fun `Search for a kNote by name`() {
        val searchParams = HashMap<String, List<String>>()
        searchParams["q"] = listOf(person1Name)
        val response = searchWithMetrics(searchParams)

        assertEquals(1, response.hits.totalHits.value)
        assertEquals(person1Name, response.hits.hits[0].sourceAsMap["name"])
    }

    @Test
    fun `Filter kNotes by type Event`() {
        val searchParams = HashMap<String, List<String>>()
        searchParams["facet.type"] = listOf("Event")
        val response = searchWithMetrics(searchParams)

        assertEquals(2, response.hits.totalHits.value)

        for(hit in response.hits.hits) {
            val kNote = gson.fromJson(hit.sourceAsString,
                    Class.forName(hit.sourceAsMap["type"] as String)) as Knote
            assertTrue(kNote is Event)
        }
    }

    @Test
    fun `Filter kNotes by type Place`() {
        val searchParams = HashMap<String, List<String>>()
        searchParams["facet.type"] = listOf("Place")
        val response = searchWithMetrics(searchParams)

        assertEquals(1, response.hits.totalHits.value)

        for(hit in response.hits.hits) {
            val kNote = gson.fromJson(hit.sourceAsString,
                    Class.forName(hit.sourceAsMap["type"] as String)) as Knote
            assertTrue(kNote is Place)
        }
    }

    @Test
    fun `Filter kNotes by binId`() {
        val searchParams = HashMap<String, List<String>>()
        searchParams["facet.binIds"] = listOf("bin1")
        val response = searchWithMetrics(searchParams)

        assertEquals(2, response.hits.totalHits.value)

        for(hit in response.hits.hits) {
            val kNote = gson.fromJson(hit.sourceAsString,
                    Class.forName(hit.sourceAsMap["type"] as String)) as Knote
            assertTrue(kNote.binIds.contains("bin1"))
        }
    }

    // Find related kNotes by name
    @Test
    fun `Find all kNotes that are related to "person1Name" (one degree of separation)`() {
        val searchParams = HashMap<String, List<String>>()
        val relationshipType = "relatedPerson"
        searchParams["relationship.$relationshipType"] = listOf(person1Name)
        val response = searchWithMetrics(searchParams)

        assertEquals(3, response.hits.totalHits.value)

        for(hit in response.hits.hits) {

            val kNote = gson.fromJson(hit.sourceAsString,
                    Class.forName(hit.sourceAsMap["type"] as String)) as Knote
            var indicatedRelationship : Relationship? = null

            kNote.relationships.forEach {
                if(it.type == relationshipType) {
                    indicatedRelationship = it
                }
            }

            assertNotNull(indicatedRelationship)

            var hasRelatedKnote = false

            indicatedRelationship?.objectKnotes?.forEach objectKnote@ {
                if(it.name == person1Name) {
                    hasRelatedKnote = true
                    return@objectKnote
                }
            }

            assertTrue(hasRelatedKnote)
        }
    }

    // Find related kNotes by name
    @Test
    fun `Find all kNotes that are related to "person1Name" (wrong relationshipType)`() {
        val searchParams = HashMap<String, List<String>>()
        val relationshipType = "relatedPlace"
        searchParams["relationship.$relationshipType"] = listOf(person1Name)
        val response = searchWithMetrics(searchParams)

        assertEquals(0, response.hits.totalHits.value)
    }

    // Find related kNotes by id
//    @Test
//    fun `Find all kNotes that are related to "event3" (one degree of separation)`() {
//        val searchParams = HashMap<String, List<String>>()
//        val relationshipType = "relatedEvent"
//        val eventId = "event3"
//        searchParams["relationship.$relationshipType"] = listOf(eventId)
//        val response = searchWithMetrics(searchParams)
//
//        assertEquals(1000, response.hits.totalHits.value)
//
//        for(hit in response.hits.hits) {
//
//            val kNote = gson.fromJson(hit.sourceAsString,
//                    Class.forName(hit.sourceAsMap["type"] as String)) as Knote
//            var indicatedRelationship : Relationship? = null
//
//            kNote.relationships.forEach {
//                if(it.type == relationshipType) {
//                    indicatedRelationship = it
//                }
//            }
//
//            assertNotNull(indicatedRelationship)
//
//            var hasRelatedKnote = false
//
//            indicatedRelationship?.objectKnotes?.forEach objectKnote@ {
//                if(it.id == eventId) {
//                    hasRelatedKnote = true
//                    return@objectKnote
//                }
//            }
//
//            assertTrue(hasRelatedKnote)
//        }
//    }

//    @Test
//    fun `Find all kNotes that occurred within a time range`() {
//        assertEquals(true, false)
//    }
//
//    @Test
//    fun `Find all kNotes that are associated with a location`() {
//        assertEquals(true, false)
//    }
}
