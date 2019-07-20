import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.jsontype.TypeSerializer
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import org.apache.http.HttpHost
import org.apache.lucene.search.join.ScoreMode
import org.elasticsearch.action.get.GetRequest
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
import org.slf4j.LoggerFactory
import java.io.IOException

operator fun Regex.contains(text: CharSequence): Boolean = this.matches(text)

//Custom Serializer to pair down the Serialized kNote object when it is used
//as part of the ObjectKnotes in the Relationship object. The relationships
//array in a kNote cannot be serialized in that context since it would create a
//circular dependency
class ObjectKnoteSerializer : JsonSerializer<Knote>() {

    @Throws(IOException::class, JsonProcessingException::class)
    override fun serialize(
        value: Knote, jgen: JsonGenerator, provider: SerializerProvider) {

        jgen.writeStringField("id", value.id)
        jgen.writeStringField("name", value.name)
    }


    //Used to generate the type field for all of the kNote types that extend
    //the abstract Knote class
    @Throws(IOException::class, JsonProcessingException::class)
    override fun serializeWithType(value: Knote, gen: JsonGenerator,
        provider: SerializerProvider, typeSer: TypeSerializer) {

        val typeId = typeSer.typeId(value, JsonToken.START_OBJECT)
        typeSer.writeTypePrefix(gen, typeId)
        serialize(value, gen, provider) // call your customized serialize method
        typeSer.writeTypeSuffix(gen, typeId)
    }
}

//Annotations Used to generate the type field for all of the kNote types that extend
//the abstract Knote class
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type")
@JsonSubTypes(value = [
    JsonSubTypes.Type(value = Place::class),
    JsonSubTypes.Type(value = Person::class),
    JsonSubTypes.Type(value = Event::class)])
abstract class Knote(val id: String, val name: String) {
    val relationships: MutableSet<Relationship> = mutableSetOf()

    fun addRelationship(relationship: Relationship) {
        this.addRelationship(relationship.type, relationship.objectKnotes)
    }

    fun addRelationship(relationshipType: String, objectKnotes: MutableCollection<Knote>) {
        val existingRelationship = this.getRelationship(relationshipType)

        //Check to see if the relationship type exists. If it does,
        //add the new related objectKnotes to that relationship object.
        //Otherwise add a new Relationship object to the relationships set
        if(existingRelationship != null) {
            this.relationships.remove(existingRelationship)

            //Only add the related object kNote if it doesn't already exist in the objectKnotes array
            objectKnotes.forEach outer@ {newKnote ->
                existingRelationship.objectKnotes.forEach {existingKnote ->
                    if(newKnote.id == existingKnote.id) {
                        return@outer
                    }
                }

                existingRelationship.objectKnotes.add(newKnote)
            }

            this.relationships.add(existingRelationship)
        } else {
            this.relationships.add(Relationship(relationshipType, objectKnotes))
        }
    }

    private fun getRelationship(relationshipType: String) : Relationship? {
        relationships.forEach {
            if(it.type == relationshipType) {
                return it
            }
        }

        return null
    }

}

class Relationship(val type: String,
                   @JsonSerialize(contentUsing = ObjectKnoteSerializer::class)
                   val objectKnotes: MutableCollection<Knote>)

class Place(id: String, name: String) : Knote(id, name)
class Person(id: String, name: String) : Knote(id, name)
class Event(id: String, name: String) : Knote(id, name)

val mapper = ObjectMapper().registerKotlinModule()
        .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)

class ElasticDAO {
    companion object {
        val logger = LoggerFactory.getLogger(ElasticDAO::class.java)
        val client = RestHighLevelClient(
                RestClient.builder(
                        HttpHost("localhost", 9200, "http"),
                        HttpHost("localhost", 9201, "http")))

        private fun createMappingJson() : String {
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
    }

    init {
        //Create the knotes index if it doesn't exist
        if(!client.indices().exists(GetIndexRequest("knotes"), RequestOptions.DEFAULT)) {
            client.indices().create(CreateIndexRequest("knotes").mapping(createMappingJson(), XContentType.JSON), RequestOptions.DEFAULT)
        }
    }

    fun getIndexMapping() : GetMappingsResponse {
        return client.indices().getMapping(GetMappingsRequest().indices("knotes"), RequestOptions.DEFAULT)
    }

    fun index(knote: Knote) : IndexResponse {
        //Create Related kNotes if they don't exist.
        //Add Opposite Relations from those kNotes to the kNote currently being indexed.
        knote.relationships.forEach {relationship ->
            relationship.objectKnotes.forEach {objectKnote ->
                //Check to see if the related kNote exists. If it does, check to see if the relationship type
                //exists on that kNote and add the source kNote to the list of related kNotes. Otherwise create a
                //new relationship object
                //Otherwise create a new kNote
                //The type should be the opposite relation of the original relation type
                try {
                    //TODO: Come up with a better way of picking the generated relationship types.
                    //The type of the two related objects probably should be taken into account
                    val relationType = "related${knote::class.simpleName}"
                    logger.info("Adding relationship \"{}\" [{}] to [{}]", relationType, objectKnote.name, knote.name)

                    val existResponse = client.get(GetRequest("knotes").id(objectKnote.id), RequestOptions.DEFAULT)

                    val relIndexRequest : IndexRequest
                    if(existResponse.isExists) {
                        val existingKnote : Knote = mapper.readValue(existResponse.sourceAsBytes)
                        existingKnote.addRelationship(relationType, mutableListOf(knote))
                        relIndexRequest = IndexRequest("knotes").id(existingKnote.id).source(mapper.writeValueAsBytes(existingKnote), XContentType.JSON)
                    } else {
                        objectKnote.addRelationship(relationType, mutableListOf(knote))
                        relIndexRequest = IndexRequest("knotes").id(objectKnote.id).source(mapper.writeValueAsBytes(objectKnote), XContentType.JSON)
                    }

                    client.index(relIndexRequest, RequestOptions.DEFAULT)
                } catch(e: Exception) {
                    logger.error("Error Indexing new Related Object kNote", e)
                    throw e
                }
            }
        }

        //Check to see if the item already exists. This would happen if a minimal view of the kNote was created
        //while processing the relationships on another kNote. If the kNote already exists, add those relationships
        //to the kNote being processed before indexing it again.
        val existResponse = client.get(GetRequest("knotes").id(knote.id), RequestOptions.DEFAULT)
        if(existResponse.isExists) {
            val existingKnote : Knote = mapper.readValue(existResponse.sourceAsBytes)

            existingKnote.relationships.forEach {
                knote.addRelationship(it)
            }
        }

        val indexRequest = IndexRequest("knotes").id(knote.id).source(mapper.writeValueAsBytes(knote), XContentType.JSON)
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

fun loadData(elasticDAO: ElasticDAO) {
    val person1 = Person("person1", "Emilie")
    person1.addRelationship("relatedPlace", mutableListOf(Place("place1", "Leesburg")))

    val event1 = Event("event1","Trying out ES")
    event1.addRelationship("relatedPlace", mutableListOf(Place("place1", "Leesburg")))
    event1.addRelationship("relatedPerson", mutableListOf(Person("person1", "Emilie")))
    val event2 = Event("event2","Going to work")
    event2.addRelationship("relatedPerson", mutableListOf(Person("person2", "Colin"), Person("person1", "Emilie")))

    elasticDAO.index(event1)
    elasticDAO.index(person1)
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

//Use Relationship Index for search, make calls to the database to retrieve full objects
