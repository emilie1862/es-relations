import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import org.apache.http.HttpHost
import org.apache.lucene.search.join.ScoreMode
import org.elasticsearch.client.RestClient
import org.elasticsearch.client.RestHighLevelClient
import org.elasticsearch.action.index.IndexRequest
import org.elasticsearch.client.RequestOptions
import org.elasticsearch.action.index.IndexResponse
import org.elasticsearch.search.builder.SearchSourceBuilder
import org.elasticsearch.action.search.SearchRequest
import org.elasticsearch.action.search.SearchResponse
import org.elasticsearch.client.indices.CreateIndexRequest
import org.elasticsearch.client.indices.GetIndexRequest
import org.elasticsearch.client.indices.GetMappingsRequest
import org.elasticsearch.client.indices.GetMappingsResponse
import org.elasticsearch.common.xcontent.XContentType
import org.elasticsearch.index.query.QueryBuilders.*

operator fun Regex.contains(text: CharSequence): Boolean = this.matches(text)

abstract class Knote(open val id: String, val type: String, open val name: String, open val relationships: Set<Relationship>?)

data class Relationship(val type: String, val objectKnotes: Collection<Knote>)

data class Place(override val id: String, override val name: String) : Knote(id, "Place", name, null)
data class Person(override val id: String, override val name: String) : Knote(id, "Person", name, null)
data class Event(override val id: String, override val name: String, override val relationships: Set<Relationship>) : Knote(id, "Event", name, relationships)

val mapper = ObjectMapper()
        .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)

class ElasticDAO {
    companion object {
        val client = RestHighLevelClient(
                RestClient.builder(
                        HttpHost("localhost", 9200, "http"),
                        HttpHost("localhost", 9201, "http")))
    }

    init {
        //Create the knotes index if it doesn't exist
        if(!client.indices().exists(GetIndexRequest("knotes"), RequestOptions.DEFAULT)) {
            client.indices().create(CreateIndexRequest("knotes").mapping(getMapping(), XContentType.JSON), RequestOptions.DEFAULT)
        }
    }

    fun getIndexMapping() : GetMappingsResponse {
        return client.indices().getMapping(GetMappingsRequest().indices("knotes"), RequestOptions.DEFAULT)
    }

    fun index(knote: Knote) : IndexResponse {
        val indexRequest = IndexRequest("knotes").id(knote.id).source(mapper.writeValueAsBytes(knote), XContentType.JSON)

        //Create Related kNotes if they don't exist.
        //Add Opposite Relations to those kNotes
//        if(knote.relationships != null) {
//            for(relation in knote.relationships) {
//                for(objectKnote in relation.objectKnotes) {
//
//                }
//            }
//        }

        return client.index(indexRequest, RequestOptions.DEFAULT)
    }

    fun search(searchParams: Set<Map.Entry<String, List<String>>>) : SearchResponse {
        val searchRequest = SearchRequest("knotes")
        val searchSourceBuilder = SearchSourceBuilder()
        val relationRegex = Regex("relationship\\.(.+)")

        if(searchParams.isEmpty()) {
            searchSourceBuilder.query(matchAllQuery())
        } else {
            val boolQuery = boolQuery()

            for((key, value) in searchParams) {
                when(key) {
                    "q" -> {
                        for(vaz in value) {
                            boolQuery.must(matchQuery("name", vaz))
                        }
                    }
                    in relationRegex -> {
                        val matchResult = relationRegex.find(key)
                        val relationshipType = matchResult?.groups?.get(1)?.value

                        for(vaz in value) {
                            boolQuery.must(nestedQuery("relationships",
                                    boolQuery().must( matchQuery("relationships.type", relationshipType))
                                            .must(nestedQuery("relationships.objectKnotes",
                                                    matchQuery("relationships.objectKnotes.name", vaz), ScoreMode.Avg)), ScoreMode.Avg))
                        }
                    }
                }
            }

            searchSourceBuilder.query(boolQuery)
        }

        searchRequest.source(searchSourceBuilder)
        return client.search(searchRequest, RequestOptions.DEFAULT)
    }
}

fun getMapping() : String {
    return """
        {
            "properties": {
                "relationships": {
                    "type": "nested",
                    "properties": {
                        "objectKnotes": {
                            "type": "nested"
                        }
                    }
                }
            }
        }
    """.trimIndent()
}


fun loadData(elasticDAO: ElasticDAO) {
    val event1 = Event("event1","Trying out ES",
            setOf(Relationship("locatedAt", listOf(Place("place1", "Leesburg"))),
                    Relationship("participant", listOf(Person("person1", "Emilie")))))
    val event2 = Event("event2","Going to work",
            setOf(Relationship("participant", listOf(Person("person2", "Colin"), Person("person1", "Emilie")))))

    elasticDAO.index(event1)
    elasticDAO.index(event2)
}

fun Application.module() {
    val elasticDAO = ElasticDAO()
    loadData(elasticDAO)

    install(DefaultHeaders)
    install(CallLogging)
    install(Routing) {
        get("/") {
            val queryParameters = call.request.queryParameters.entries()
            call.respondText(elasticDAO.search(queryParameters).toString(), ContentType.Application.Json)
        }
        get("/mapping") {
            val response = elasticDAO.getIndexMapping()
            call.respondText(mapper.writeValueAsString(response), ContentType.Application.Json)
        }
    }
}

fun main(args: Array<String>) {
    embeddedServer(Netty, port = 8081, watchPaths = listOf("TestAppKt"), module = Application::module).start()
}

//Use Relationsip Index for search, make calls to the database to retrieve full objects
