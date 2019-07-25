import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.jsontype.TypeSerializer
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
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

internal val mapper = ObjectMapper().registerKotlinModule()
        .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)

class ElasticDAO {
    companion object {
        val logger = LoggerFactory.getLogger(ElasticDAO::class.java)
        val client = RestHighLevelClient(
                RestClient.builder(
                        HttpHost("localhost", 9200, "http"),
                        HttpHost("localhost", 9201, "http")))

        val mappingJson = """
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

    init {
        //Create the knotes index if it doesn't exist
        if(!client.indices().exists(GetIndexRequest("knotes"), RequestOptions.DEFAULT)) {
            client.indices().create(CreateIndexRequest("knotes").mapping(mappingJson, XContentType.JSON), RequestOptions.DEFAULT)
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

                    val relIndexRequest : IndexRequest =
                        if(existResponse.isExists) {
                            val existingKnote : Knote = mapper.readValue(existResponse.sourceAsBytes)
                            existingKnote.addRelationship(relationType, mutableListOf(knote))
                            IndexRequest("knotes").id(existingKnote.id).source(mapper.writeValueAsBytes(existingKnote), XContentType.JSON)
                        } else {
                            objectKnote.addRelationship(relationType, mutableListOf(knote))
                            IndexRequest("knotes").id(objectKnote.id).source(mapper.writeValueAsBytes(objectKnote), XContentType.JSON)
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

    fun search(searchParams: Map<String, List<String>>) : SearchResponse {
        val searchRequest = SearchRequest("knotes")
        val searchSourceBuilder = SearchSourceBuilder()
        val relationRegex = Regex("relationship\\.(.+)")
        val facetRegex = Regex("facet\\.(.+)")

        if(searchParams.isEmpty()) {
            searchSourceBuilder.query(matchAllQuery())
        } else {
            val boolQuery = boolQuery()

            for((key, value) in searchParams) {
                if(key == "q") {
                    for(vaz in value) {
                        boolQuery.must(matchQuery("name", vaz))
                    }
                }

                //Facet by top level fields. i.e. type, binId
                val matchFacetResult = facetRegex.find(key)
                if(matchFacetResult != null) {
                    val facetField = matchFacetResult.groups[1]?.value

                    for(vaz in value) {
                        boolQuery.must(matchQuery(facetField, vaz))
                    }
                }

                val matchResult = relationRegex.find(key)
                if(matchResult != null) {
                    val relationshipType = matchResult.groups[1]?.value

                    for(vaz in value) {
                        boolQuery.must(nestedQuery("relationships",
                                boolQuery().must( matchQuery("relationships.type", relationshipType))
                                        .must(nestedQuery("relationships.objectKnotes",
                                                matchQuery("relationships.objectKnotes.name", vaz), ScoreMode.Avg)), ScoreMode.Avg))
                    }
                }
            }

            searchSourceBuilder.query(boolQuery)
        }

        searchRequest.source(searchSourceBuilder)
        return client.search(searchRequest, RequestOptions.DEFAULT)
    }
}

//Use Relationship Index for search, make calls to the database to retrieve full objects
