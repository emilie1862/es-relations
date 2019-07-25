import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeAll

val person1Name = "Emilie"
val person2Name = "Colin"

internal class ElasticDAOTest {

    companion object {
        lateinit var elasticDAO: ElasticDAO

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

            elasticDAO.index(event1)
            elasticDAO.index(person1)
            elasticDAO.index(event2)
        }

    }

    @Test
    fun `Match all kNotes`() {
        val searchParams = HashMap<String, List<String>>()
        val response = elasticDAO.search(searchParams)

        Assertions.assertEquals(5, response.hits.totalHits.value)
    }

    @Test
    fun `Search for a kNote by name`() {
        val searchParams = HashMap<String, List<String>>()
        searchParams["q"] = listOf(person1Name)
        val response = elasticDAO.search(searchParams)

        Assertions.assertEquals(1, response.hits.totalHits.value)
        Assertions.assertEquals(person1Name, response.hits.hits[0].sourceAsMap["name"])
    }

    @Test
    fun `Filter kNotes by type`() {
        val searchParams = HashMap<String, List<String>>()
        searchParams["facet.type"] = listOf("Event")
        val response = elasticDAO.search(searchParams)

        Assertions.assertEquals(2, response.hits.totalHits.value)

        for(hit in response.hits.hits) {
            val kNote = mapper.readValue(hit.sourceAsString.byteInputStream(), Knote::class.java)
            Assertions.assertTrue(kNote is Event)
        }
    }

    @Test
    fun `Filter kNotes by binId`() {
        val searchParams = HashMap<String, List<String>>()
        searchParams["facet.binIds"] = listOf("bin1")
        val response = elasticDAO.search(searchParams)

        Assertions.assertEquals(2, response.hits.totalHits.value)

        for(hit in response.hits.hits) {
            val kNote = mapper.readValue(hit.sourceAsString.byteInputStream(), Knote::class.java)
            Assertions.assertTrue(kNote.binIds.contains("bin1"))
        }
    }

    @Test
    fun `Find all kNotes that are related to "person1Name" (one degree of separation)`() {
        val searchParams = HashMap<String, List<String>>()
        val relationshipType = "relatedPerson"
        searchParams["relationship.$relationshipType"] = listOf(person1Name)
        val response = elasticDAO.search(searchParams)

        Assertions.assertEquals(3, response.hits.totalHits.value)

        for(hit in response.hits.hits) {

            val kNote = mapper.readValue(hit.sourceAsString.byteInputStream(), Knote::class.java)
            var indicatedRelationship : Relationship? = null

            kNote.relationships.forEach {
                if(it.type == relationshipType) {
                    indicatedRelationship = it
                }
            }

            Assertions.assertNotNull(indicatedRelationship)

            var hasRelatedKnote = false

            indicatedRelationship?.objectKnotes?.forEach objectKnote@ {
                if(it.name == person1Name) {
                    hasRelatedKnote = true
                    return@objectKnote
                }
            }

            Assertions.assertTrue(hasRelatedKnote)
        }
    }
}
