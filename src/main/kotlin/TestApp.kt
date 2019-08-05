import com.google.gson.*
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

internal val gson = Gson()

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

    fun destroy() {
        client.close()
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
//                    logger.info("Adding relationship \"{}\" [{}] to [{}]", relationType, objectKnote.name, knote.name)

                    val existResponse = client.get(GetRequest("knotes").id(objectKnote.id), RequestOptions.DEFAULT)

                    //Check to see if the object kNote exists. If it does, add a relation to the current kNote
                    //Otherwise, create an entry in the index for that kNote (with the relation to the current kNote)
                    val relIndexRequest : IndexRequest =
                        if(existResponse.isExists) {
                            val existingKnote : Knote = gson.fromJson(existResponse.sourceAsString,
                                    Class.forName(existResponse.sourceAsMap["type"] as String)) as Knote
                            existingKnote.addRelationship(relationType, mutableListOf(knote))
                            IndexRequest("knotes").id(existingKnote.id).source(gson.toJson(existingKnote), XContentType.JSON)
                        } else {
                            objectKnote.addRelationship(relationType, mutableListOf(knote))
                            IndexRequest("knotes").id(objectKnote.id).source(gson.toJson(objectKnote), XContentType.JSON)
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
            val existingKnote : Knote = gson.fromJson(existResponse.sourceAsString,
                    Class.forName(existResponse.sourceAsMap["type"] as String)) as Knote

            existingKnote.relationships.forEach {
                knote.addRelationship(it)
            }
        }

        val indexRequest = IndexRequest("knotes").id(knote.id).source(gson.toJson(knote), XContentType.JSON)
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
                                                boolQuery().should(
                                                        matchQuery("relationships.objectKnotes.name", vaz)
                                                ).should(
                                                        matchQuery("relationships.objectKnotes.id", vaz)
                                                ), ScoreMode.Avg)), ScoreMode.Avg))
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

fun main(args: Array<String>) {
    val elasticDAO = ElasticDAO()
    loadData(elasticDAO)
    elasticDAO.destroy()
}

//Use Relationship Index for search, make calls to the database to retrieve full objects
